package com.cloudpulse.order.service;

import com.cloudpulse.order.api.CreateOrderRequest;
import com.cloudpulse.order.api.OrderNotFoundException;
import com.cloudpulse.order.api.OrderResponse;
import com.cloudpulse.order.api.PageResponse;
import com.cloudpulse.order.domain.Order;
import com.cloudpulse.order.domain.OutboxEvent;
import com.cloudpulse.order.events.EventEnvelope;
import com.cloudpulse.order.events.OrderCreatedData;
import com.cloudpulse.order.events.PubSubProperties;
import com.cloudpulse.order.repo.OrderRepository;
import com.cloudpulse.order.repo.OutboxRepository;
import com.cloudpulse.order.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    public static final String EVENT_TYPE = "order.created";

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final PubSubProperties pubsub;
    private final Counter ordersCreated;

    public OrderService(OrderRepository orders, OutboxRepository outbox, ObjectMapper mapper,
                        PubSubProperties pubsub, MeterRegistry meters) {
        this.orders = orders;
        this.outbox = outbox;
        this.mapper = mapper;
        this.pubsub = pubsub;
        this.ordersCreated = Counter.builder("business.orders.accepted").description("Orders accepted").register(meters);
    }

    /** Persists the order and its outbox event in ONE transaction (no dual-write inconsistency). */
    @Transactional
    public OrderResponse create(CreateOrderRequest req, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = orders.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("idempotent replay orderId={}", existing.get().getOrderId());
                return OrderResponse.from(existing.get());
            }
        } else {
            idempotencyKey = null;
        }

        Instant now = Instant.now();
        Order order = new Order(newOrderId(), req.customerId(), req.currency(), idempotencyKey, now);
        req.items().forEach(i -> order.addItem(i.sku(), i.quantity(), i.unitPrice()));
        orders.save(order);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY) != null
                ? MDC.get(CorrelationIdFilter.MDC_KEY) : UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();
        var data = new OrderCreatedData(order.getOrderId(), order.getCustomerId(),
                order.getItems().stream().map(i -> new OrderCreatedData.Line(i.getSku(), i.getQuantity(), i.getUnitPrice())).toList(),
                order.getTotalAmount(), order.getCurrency());
        var envelope = new EventEnvelope<>(eventId.toString(), EVENT_TYPE, 1, now, correlationId, EventEnvelope.SOURCE, data);
        outbox.save(new OutboxEvent(eventId, pubsub.ordersTopic(), EVENT_TYPE, correlationId, toJson(envelope), now));

        ordersCreated.increment();
        log.info("order created orderId={} customerId={} total={}", order.getOrderId(), order.getCustomerId(), order.getTotalAmount());
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String orderId) {
        return orders.findByOrderId(orderId).map(OrderResponse::from).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> list(Pageable pageable) {
        return PageResponse.of(orders.findAll(pageable).map(OrderResponse::from));
    }

    private String newOrderId() {
        return "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event", e);
        }
    }
}
