package com.cloudpulse.analytics.events;

import com.cloudpulse.analytics.store.AnalyticsWriter;
import com.cloudpulse.analytics.store.Rows.InventoryRow;
import com.cloudpulse.analytics.store.Rows.MetricRow;
import com.cloudpulse.analytics.store.Rows.OrderRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Routes validated events to the analytics store. Invalid input throws (nack -> retry -> DLQ);
 * duplicates are reported as DUPLICATE and acked. The Pub/Sub message is acked only after the write.
 */
@Component
public class EventRouter {
    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    public enum Outcome { WRITTEN, DUPLICATE }

    private final AnalyticsWriter writer;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public EventRouter(AnalyticsWriter writer, ObjectMapper mapper, MeterRegistry meters) {
        this.writer = writer;
        this.mapper = mapper;
        this.meters = meters;
    }

    public Outcome route(String json) throws JsonProcessingException {
        JsonNode root = mapper.readTree(json);
        String eventId = req(root, "eventId");
        String type = req(root, "eventType");
        Instant occurredAt = Instant.parse(req(root, "occurredAt"));
        String corr = root.path("correlationId").asText(null);
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) throw new IllegalArgumentException("missing data");
        if (corr != null) MDC.put("correlationId", corr);
        try {
            boolean written = switch (type) {
                case "order.created" -> writer.write(new OrderRow(eventId, occurredAt, corr, req(data, "orderId"),
                        data.path("customerId").asText(null),
                        data.hasNonNull("totalAmount") ? new BigDecimal(data.get("totalAmount").asText()) : null,
                        data.path("currency").asText(null), mapper.writeValueAsString(data)));
                case "inventory.updated" -> writer.write(new InventoryRow(eventId, occurredAt, corr, req(data, "orderId"),
                        req(data, "status"), data.path("reason").asText(null), mapper.writeValueAsString(data)));
                case "metric.sample" -> writer.write(new MetricRow(eventId, occurredAt,
                        data.hasNonNull("observedAt") ? Instant.parse(data.get("observedAt").asText()) : occurredAt,
                        req(data, "service"), req(data, "metric"), num(data, "value"), data.path("unit").asText(null),
                        data.path("synthetic").asBoolean(true)));
                default -> throw new IllegalArgumentException("unsupported eventType " + type);
            };
            if (written) {
                meters.counter("sink.events.written", "type", type).increment();
                // End-to-end freshness: event time -> analytics store.
                Timer.builder("sink.pipeline.latency").tag("type", type).publishPercentiles(0.5, 0.95)
                        .register(meters).record(Duration.between(occurredAt, Instant.now()).abs());
                log.debug("written type={} eventId={}", type, eventId);
                return Outcome.WRITTEN;
            }
            meters.counter("sink.events.duplicates", "type", type).increment();
            return Outcome.DUPLICATE;
        } finally {
            MDC.remove("correlationId");
        }
    }

    private static String req(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + field);
        return v;
    }

    private static double num(JsonNode n, String field) {
        if (!n.hasNonNull(field) || !n.get(field).isNumber()) throw new IllegalArgumentException("missing numeric " + field);
        return n.get(field).asDouble();
    }
}
