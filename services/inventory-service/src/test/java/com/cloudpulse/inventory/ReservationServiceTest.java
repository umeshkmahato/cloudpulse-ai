package com.cloudpulse.inventory;

import com.cloudpulse.inventory.domain.OutboxEvent;
import com.cloudpulse.inventory.domain.StockItem;
import com.cloudpulse.inventory.events.PubSubProperties;
import com.cloudpulse.inventory.repo.OutboxRepository;
import com.cloudpulse.inventory.repo.ProcessedEventRepository;
import com.cloudpulse.inventory.repo.ReservationRepository;
import com.cloudpulse.inventory.repo.StockRepository;
import com.cloudpulse.inventory.service.ReservationService;
import com.cloudpulse.inventory.service.ReservationService.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class ReservationServiceTest {
    private final StockRepository stock = mock(StockRepository.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ReservationService service;
    private StockItem red;

    @BeforeEach
    void setUp() {
        service = new ReservationService(stock, processed, reservations, outbox, mapper,
                new PubSubProperties("p", "", "inventory-updated", "sub", false), new SimpleMeterRegistry());
        red = new StockItem("SKU-RED", "red", 10, Instant.now());
        when(stock.lockBySkus(anyCollection())).thenReturn(List.of(red));
    }

    private String event(String eventId, String orderId, String sku, int qty) {
        return """
            {"eventId":"%s","eventType":"order.created","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",
             "correlationId":"corr-9","source":"order-service","futureField":1,
             "data":{"orderId":"%s","customerId":"C","items":[{"sku":"%s","quantity":%d,"unitPrice":1.0}],"totalAmount":1,"currency":"USD"}}
            """.formatted(eventId, orderId, sku, qty);
    }

    @Test
    void reservesStockAndPublishesInventoryUpdatedWithSameCorrelationId() throws Exception {
        assertThat(service.handle(event("e1", "ORD-1", "SKU-RED", 4))).isEqualTo(Outcome.RESERVED);

        assertThat(red.getAvailable()).isEqualTo(6);
        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(cap.capture());
        JsonNode json = mapper.readTree(cap.getValue().getPayload());
        assertThat(json.get("eventType").asText()).isEqualTo("inventory.updated");
        assertThat(json.get("correlationId").asText()).isEqualTo("corr-9");
        assertThat(json.get("data").get("status").asText()).isEqualTo("RESERVED");
        assertThat(json.get("data").get("items").get(0).get("remainingAvailable").asInt()).isEqualTo(6);
        verify(processed).save(any());
    }

    @Test
    void insufficientStockRejectsWithoutChangingStock() throws Exception {
        assertThat(service.handle(event("e2", "ORD-2", "SKU-RED", 11))).isEqualTo(Outcome.REJECTED);
        assertThat(red.getAvailable()).isEqualTo(10);
        ArgumentCaptor<OutboxEvent> cap = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(cap.capture());
        assertThat(mapper.readTree(cap.getValue().getPayload()).get("data").get("reason").asText())
                .isEqualTo("INSUFFICIENT_STOCK:SKU-RED");
    }

    @Test
    void unknownSkuIsRejected() throws Exception {
        assertThat(service.handle(event("e3", "ORD-3", "SKU-NOPE", 1))).isEqualTo(Outcome.REJECTED);
        assertThat(red.getAvailable()).isEqualTo(10);
    }

    @Test
    void duplicateEventIdIsIgnoredWithoutSideEffects() throws Exception {
        when(processed.existsById("e1")).thenReturn(true);
        assertThat(service.handle(event("e1", "ORD-1", "SKU-RED", 4))).isEqualTo(Outcome.DUPLICATE);
        assertThat(red.getAvailable()).isEqualTo(10);
        verify(outbox, never()).save(any());
        verify(reservations, never()).save(any());
    }

    @Test
    void sameOrderUnderNewEventIdIsAlsoIgnored() throws Exception {
        when(reservations.existsById("ORD-1")).thenReturn(true);
        assertThat(service.handle(event("e-new", "ORD-1", "SKU-RED", 4))).isEqualTo(Outcome.DUPLICATE);
        assertThat(red.getAvailable()).isEqualTo(10);
    }

    @Test
    void malformedPayloadThrowsSoMessageIsNackedAndEventuallyDeadLettered() {
        assertThatThrownBy(() -> service.handle("{not json")).isInstanceOf(Exception.class);
    }
}
