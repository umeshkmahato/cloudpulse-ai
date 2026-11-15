package com.cloudpulse.order.service;

import com.cloudpulse.order.domain.Order;
import com.cloudpulse.order.domain.OrderStatus;
import com.cloudpulse.order.domain.ProcessedEvent;
import com.cloudpulse.order.events.EventEnvelope;
import com.cloudpulse.order.events.InventoryUpdatedData;
import com.cloudpulse.order.repo.OrderRepository;
import com.cloudpulse.order.repo.ProcessedEventRepository;
import com.cloudpulse.order.web.CorrelationIdFilter;
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

/**
 * Applies inventory.updated to the order. Idempotent (eventId marker + terminal-state guard).
 * Unknown order or invalid event throws -> nack -> retry with backoff -> dead-letter topic.
 */
@Service
public class OrderStatusService {
    private static final Logger log = LoggerFactory.getLogger(OrderStatusService.class);

    public enum Outcome { CONFIRMED, REJECTED, DUPLICATE, IGNORED_NOT_PENDING }

    private final OrderRepository orders;
    private final ProcessedEventRepository processed;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public OrderStatusService(OrderRepository orders, ProcessedEventRepository processed, ObjectMapper mapper, MeterRegistry meters) {
        this.orders = orders;
        this.processed = processed;
        this.mapper = mapper;
        this.meters = meters;
    }

    @Transactional
    public Outcome apply(String json) throws JsonProcessingException {
        EventEnvelope<InventoryUpdatedData> env = mapper.readValue(json, new TypeReference<>() {});
        if (env.correlationId() != null) MDC.put(CorrelationIdFilter.MDC_KEY, env.correlationId());
        try {
            if (!"inventory.updated".equals(env.eventType()) || env.data() == null || env.data().orderId() == null) {
                throw new IllegalArgumentException("not a valid inventory.updated event");
            }
            if (processed.existsById(env.eventId())) {
                meters.counter("orders.events.duplicates").increment();
                return Outcome.DUPLICATE;
            }
            Order order = orders.findByOrderId(env.data().orderId())
                    .orElseThrow(() -> new IllegalStateException("unknown order " + env.data().orderId()));
            processed.save(new ProcessedEvent(env.eventId(), Instant.now()));
            if (order.getStatus() != OrderStatus.PENDING) {
                log.info("order already {} - ignoring orderId={}", order.getStatus(), order.getOrderId());
                return Outcome.IGNORED_NOT_PENDING;
            }
            boolean reserved = "RESERVED".equals(env.data().status());
            if (reserved) order.confirm(); else order.reject(env.data().reason());
            meters.counter("business.orders.finalized", "status", order.getStatus().name()).increment();
            log.info("order finalized orderId={} status={} reason={}", order.getOrderId(), order.getStatus(), env.data().reason());
            return reserved ? Outcome.CONFIRMED : Outcome.REJECTED;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
