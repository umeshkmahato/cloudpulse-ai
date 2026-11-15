package com.cloudpulse.inventory;

import com.cloudpulse.inventory.domain.OutboxEvent;
import com.cloudpulse.inventory.domain.StockItem;
import com.cloudpulse.inventory.events.PubSubProperties;
import com.cloudpulse.inventory.repo.*;
import com.cloudpulse.inventory.service.ReservationService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class EventContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); // same as Spring Boot default

    private JsonSchema schema(String name) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("../../architecture/event-schemas/" + name))) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
        }
    }

    private JsonNode produce(int stock, int qty) throws Exception {
        StockRepository stockRepo = mock(StockRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(stockRepo.lockBySkus(anyCollection())).thenReturn(List.of(new StockItem("SKU-1", "d", stock, Instant.now())));
        var svc = new ReservationService(stockRepo, mock(ProcessedEventRepository.class), mock(ReservationRepository.class), outbox,
                mapper, new PubSubProperties("p", "", "inventory-updated", "s", false), new SimpleMeterRegistry());
        svc.handle("""
            {"eventId":"e1","eventType":"order.created","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z","correlationId":"c1",
             "source":"order-service","data":{"orderId":"ORD-ABC123456789","customerId":"C","totalAmount":1,"currency":"USD",
             "items":[{"sku":"SKU-1","quantity":%d,"unitPrice":1.0}]}}""".formatted(qty));
        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(cap.capture());
        return mapper.readTree(cap.getValue().getPayload());
    }

    @Test
    void reservedAndRejectedEventsMatchSchema() throws Exception {
        assertThat(schema("inventory.updated.v1.schema.json").validate(produce(10, 2))).isEmpty();
        assertThat(schema("inventory.updated.v1.schema.json").validate(produce(1, 5))).isEmpty();
    }

    @Test
    void orderCreatedSampleConsumedHereMatchesProducerSchema() throws Exception {
        JsonNode sample = mapper.readTree("""
            {"eventId":"e1","eventType":"order.created","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z","correlationId":"c1",
             "source":"order-service","data":{"orderId":"ORD-ABC123456789","customerId":"C","totalAmount":1,"currency":"USD",
             "items":[{"sku":"SKU-1","quantity":1,"unitPrice":1.0}]}}""");
        assertThat(schema("order.created.v1.schema.json").validate(sample)).isEmpty();
    }
}
