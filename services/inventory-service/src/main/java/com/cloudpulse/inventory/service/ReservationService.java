package com.cloudpulse.inventory.service;

import com.cloudpulse.inventory.domain.OutboxEvent;
import com.cloudpulse.inventory.domain.ProcessedEvent;
import com.cloudpulse.inventory.domain.Reservation;
import com.cloudpulse.inventory.domain.StockItem;
import com.cloudpulse.inventory.events.EventEnvelope;
import com.cloudpulse.inventory.events.InventoryUpdatedData;
import com.cloudpulse.inventory.events.OrderCreatedData;
import com.cloudpulse.inventory.events.PubSubProperties;
import com.cloudpulse.inventory.repo.OutboxRepository;
import com.cloudpulse.inventory.repo.ProcessedEventRepository;
import com.cloudpulse.inventory.repo.ReservationRepository;
import com.cloudpulse.inventory.repo.StockRepository;
import com.cloudpulse.inventory.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Handles order.created idempotently. Everything below happens in ONE DB transaction:
 * dedupe marker + stock decrement + reservation record + outbox event. The Pub/Sub ack happens
 * only after commit (see OrderEventListener), so a crash can only cause a harmless redelivery.
 */
@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    public static final String EVENT_TYPE = "inventory.updated";

    public enum Outcome { RESERVED, REJECTED, DUPLICATE }

    private final StockRepository stock;
    private final ProcessedEventRepository processed;
    private final ReservationRepository reservations;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final PubSubProperties props;
    private final MeterRegistry meters;

    public ReservationService(StockRepository stock, ProcessedEventRepository processed, ReservationRepository reservations,
                              OutboxRepository outbox, ObjectMapper mapper, PubSubProperties props, MeterRegistry meters) {
        this.stock = stock;
        this.processed = processed;
        this.reservations = reservations;
        this.outbox = outbox;
        this.mapper = mapper;
        this.props = props;
        this.meters = meters;
    }

    @Transactional
    public Outcome handle(String json) throws JsonProcessingException {
        EventEnvelope<OrderCreatedData> env = mapper.readValue(json, new TypeReference<>() {});
        MDC.put(CorrelationIdFilter.MDC_KEY, env.correlationId());
        try {
            if (processed.existsById(env.eventId()) || reservations.existsById(env.data().orderId())) {
                meters.counter("inventory.events.duplicates").increment();
                log.info("duplicate event ignored eventId={} orderId={}", env.eventId(), env.data().orderId());
                return Outcome.DUPLICATE;
            }
            Instant now = Instant.now();
            processed.save(new ProcessedEvent(env.eventId(), now));

            Map<String, Integer> wanted = new TreeMap<>();
            for (var line : env.data().items()) wanted.merge(line.sku(), line.quantity(), Integer::sum);

            Map<String, StockItem> locked = new HashMap<>();
            stock.lockBySkus(wanted.keySet()).forEach(s -> locked.put(s.getSku(), s));

            String reason = null;
            for (var e : wanted.entrySet()) {
                StockItem s = locked.get(e.getKey());
                if (s == null) { reason = "UNKNOWN_SKU:" + e.getKey(); break; }
                if (!s.canReserve(e.getValue())) { reason = "INSUFFICIENT_STOCK:" + e.getKey(); break; }
            }
            boolean ok = reason == null;
            if (ok) wanted.forEach((sku, qty) -> locked.get(sku).reserve(qty, now));

            String status = ok ? "RESERVED" : "REJECTED";
            reservations.save(new Reservation(env.data().orderId(), status, reason, now));

            List<InventoryUpdatedData.Line> lines = wanted.entrySet().stream()
                    .map(e -> new InventoryUpdatedData.Line(e.getKey(), e.getValue(),
                            locked.containsKey(e.getKey()) ? locked.get(e.getKey()).getAvailable() : 0))
                    .toList();
            UUID id = UUID.randomUUID();
            var out = new EventEnvelope<>(id.toString(), EVENT_TYPE, 1, now, env.correlationId(),
                    EventEnvelope.SOURCE, new InventoryUpdatedData(env.data().orderId(), status, reason, lines));
            outbox.save(new OutboxEvent(id, props.publishTopic(), EVENT_TYPE, env.correlationId(), mapper.writeValueAsString(out), now));

            meters.counter("inventory.reservations", "result", status).increment();
            log.info("order processed orderId={} status={} reason={}", env.data().orderId(), status, reason);
            return ok ? Outcome.RESERVED : Outcome.REJECTED;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
