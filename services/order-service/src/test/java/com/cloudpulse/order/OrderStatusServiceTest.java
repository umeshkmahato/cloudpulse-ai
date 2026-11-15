package com.cloudpulse.order;

import com.cloudpulse.order.domain.Order;
import com.cloudpulse.order.domain.OrderStatus;
import com.cloudpulse.order.repo.OrderRepository;
import com.cloudpulse.order.repo.ProcessedEventRepository;
import com.cloudpulse.order.service.OrderStatusService;
import com.cloudpulse.order.service.OrderStatusService.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderStatusServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private OrderStatusService service;
    private Order order;

    @BeforeEach
    void setUp() {
        service = new OrderStatusService(orders, processed,
                new ObjectMapper().registerModule(new JavaTimeModule()), new SimpleMeterRegistry());
        order = new Order("ORD-1", "C", "USD", null, Instant.now());
        order.addItem("SKU-1", 1, new BigDecimal("5.00"));
        when(orders.findByOrderId("ORD-1")).thenReturn(Optional.of(order));
    }

    private String event(String id, String status, String reason) {
        return """
            {"eventId":"%s","eventType":"inventory.updated","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
             "correlationId":"c1","source":"inventory-service",
             "data":{"orderId":"ORD-1","status":"%s","reason":%s,"items":[]}}
            """.formatted(id, status, reason == null ? "null" : "\"" + reason + "\"");
    }

    @Test
    void reservedConfirmsOrder() throws Exception {
        assertThat(service.apply(event("e1", "RESERVED", null))).isEqualTo(Outcome.CONFIRMED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void rejectedStoresReason() throws Exception {
        assertThat(service.apply(event("e2", "REJECTED", "INSUFFICIENT_STOCK:SKU-1"))).isEqualTo(Outcome.REJECTED);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getStatusReason()).isEqualTo("INSUFFICIENT_STOCK:SKU-1");
    }

    @Test
    void duplicateEventIsIgnored() throws Exception {
        when(processed.existsById("e1")).thenReturn(true);
        assertThat(service.apply(event("e1", "RESERVED", null))).isEqualTo(Outcome.DUPLICATE);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(processed, never()).save(any());
    }

    @Test
    void terminalOrderIsNotChangedByLaterEvent() throws Exception {
        service.apply(event("e1", "RESERVED", null));
        assertThat(service.apply(event("e9", "REJECTED", "LATE"))).isEqualTo(Outcome.IGNORED_NOT_PENDING);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void unknownOrderThrowsSoMessageRetriesThenDeadLetters() {
        when(orders.findByOrderId("ORD-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.apply(event("e3", "RESERVED", null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongEventTypeIsRejected() {
        assertThatThrownBy(() -> service.apply(event("e4", "RESERVED", null).replace("inventory.updated", "x.y")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
