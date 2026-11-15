package com.cloudpulse.order;

import com.cloudpulse.order.api.CreateOrderRequest;
import com.cloudpulse.order.api.OrderNotFoundException;
import com.cloudpulse.order.api.OrderResponse;
import com.cloudpulse.order.domain.Order;
import com.cloudpulse.order.domain.OutboxEvent;
import com.cloudpulse.order.events.PubSubProperties;
import com.cloudpulse.order.repo.OrderRepository;
import com.cloudpulse.order.repo.OutboxRepository;
import com.cloudpulse.order.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private OrderService service;

    private final CreateOrderRequest request = new CreateOrderRequest("CUST-1", "USD", List.of(
            new CreateOrderRequest.Item("SKU-1", 2, new BigDecimal("10.50")),
            new CreateOrderRequest.Item("SKU-2", 1, new BigDecimal("4.00"))));

    @BeforeEach
    void setUp() {
        service = new OrderService(orders, outbox, mapper,
                new PubSubProperties("p", "", "orders-created", "sub", false), new SimpleMeterRegistry());
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void createPersistsOrderAndOutboxEventWithEnvelope() throws Exception {
        OrderResponse r = service.create(request, null);

        assertThat(r.orderId()).startsWith("ORD-");
        assertThat(r.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(r.status()).isEqualTo("PENDING");

        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(cap.capture());
        OutboxEvent e = cap.getValue();
        assertThat(e.getTopic()).isEqualTo("orders-created");
        JsonNode json = mapper.readTree(e.getPayload());
        assertThat(json.get("eventId").asText()).isEqualTo(e.getId().toString());
        assertThat(json.get("eventType").asText()).isEqualTo("order.created");
        assertThat(json.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.get("source").asText()).isEqualTo("order-service");
        assertThat(json.get("correlationId").asText()).isNotBlank();
        assertThat(json.get("data").get("orderId").asText()).isEqualTo(r.orderId());
        assertThat(json.get("data").get("items")).hasSize(2);
    }

    @Test
    void idempotencyKeyReplayReturnsExistingOrderWithoutNewEvent() {
        Order existing = new Order("ORD-EXISTING01", "CUST-1", "USD", "key-1", Instant.now());
        existing.addItem("SKU-1", 1, new BigDecimal("5.00"));
        when(orders.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        OrderResponse r = service.create(request, "key-1");

        assertThat(r.orderId()).isEqualTo("ORD-EXISTING01");
        verify(orders, never()).save(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void getUnknownOrderThrowsNotFound() {
        when(orders.findByOrderId("ORD-NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("ORD-NOPE")).isInstanceOf(OrderNotFoundException.class);
    }
}
