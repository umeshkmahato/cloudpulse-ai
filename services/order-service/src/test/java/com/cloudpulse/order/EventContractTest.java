package com.cloudpulse.order;

import com.cloudpulse.order.api.CreateOrderRequest;
import com.cloudpulse.order.domain.Order;
import com.cloudpulse.order.domain.OutboxEvent;
import com.cloudpulse.order.events.PubSubProperties;
import com.cloudpulse.order.repo.OrderRepository;
import com.cloudpulse.order.repo.OutboxRepository;
import com.cloudpulse.order.service.OrderService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Contract tests against the shared JSON Schemas in architecture/event-schemas. */
class EventContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // same as Spring Boot default

    private JsonSchema schema(String name) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("../../architecture/event-schemas/" + name))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
        }
    }

    @Test
    void producedOrderCreatedMatchesSchema() throws Exception {
        OrderRepository orders = mock(OrderRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
        var service = new OrderService(orders, outbox, mapper, new PubSubProperties("p", "", "orders-created", "s", false),
                new SimpleMeterRegistry());
        service.create(new CreateOrderRequest("C-1", "USD", List.of(new CreateOrderRequest.Item("SKU-1", 2, new BigDecimal("9.99")))), null);

        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(cap.capture());
        JsonNode payload = mapper.readTree(cap.getValue().getPayload());
        assertThat(schema("order.created.v1.schema.json").validate(payload)).isEmpty();
    }

    @Test
    void schemaRejectsBrokenOrderCreated() throws Exception {
        JsonNode bad = mapper.readTree("""
            {"eventId":"e","eventType":"order.created","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
             "source":"order-service","data":{"orderId":"nope","customerId":"c","items":[],"totalAmount":1,"currency":"usd"}}""");
        assertThat(schema("order.created.v1.schema.json").validate(bad)).isNotEmpty();   // no correlationId, bad id/items/currency
    }

    @Test
    void inventoryUpdatedSampleUsedByConsumerTestsMatchesProducerSchema() throws Exception {
        JsonNode sample = mapper.readTree("""
            {"eventId":"e1","eventType":"inventory.updated","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
             "correlationId":"c1","source":"inventory-service",
             "data":{"orderId":"ORD-ABC123456789","status":"REJECTED","reason":"INSUFFICIENT_STOCK:SKU-1",
                     "items":[{"sku":"SKU-1","quantity":1,"remainingAvailable":0}]}}""");
        assertThat(schema("inventory.updated.v1.schema.json").validate(sample)).isEmpty();
    }
}
