package com.cloudpulse.analytics;

import com.cloudpulse.analytics.events.EventRouter;
import com.cloudpulse.analytics.events.EventRouter.Outcome;
import com.cloudpulse.analytics.store.AnalyticsWriter;
import com.cloudpulse.analytics.store.Rows.InventoryRow;
import com.cloudpulse.analytics.store.Rows.MetricRow;
import com.cloudpulse.analytics.store.Rows.OrderRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventRouterTest {
    private final AnalyticsWriter writer = mock(AnalyticsWriter.class);
    private EventRouter router;

    @BeforeEach
    void setUp() {
        router = new EventRouter(writer, new ObjectMapper(), new SimpleMeterRegistry());
        when(writer.write(any(OrderRow.class))).thenReturn(true);
        when(writer.write(any(InventoryRow.class))).thenReturn(true);
        when(writer.write(any(MetricRow.class))).thenReturn(true);
    }

    private String env(String type, String data) {
        return """
            {"eventId":"e1","eventType":"%s","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z","correlationId":"c1",
             "source":"x","data":%s}""".formatted(type, data);
    }

    @Test
    void routesOrderCreated() throws Exception {
        var out = router.route(env("order.created",
                "{\"orderId\":\"ORD-1\",\"customerId\":\"C\",\"totalAmount\":39.98,\"currency\":\"USD\",\"items\":[]}"));
        assertThat(out).isEqualTo(Outcome.WRITTEN);
        ArgumentCaptor<OrderRow> cap = ArgumentCaptor.forClass(OrderRow.class);
        verify(writer).write(cap.capture());
        assertThat(cap.getValue().totalAmount()).isEqualByComparingTo("39.98");
        assertThat(cap.getValue().correlationId()).isEqualTo("c1");
        assertThat(cap.getValue().payloadJson()).contains("ORD-1");
    }

    @Test
    void routesInventoryUpdatedAndMetricSampleKeepingSyntheticFlag() throws Exception {
        router.route(env("inventory.updated", "{\"orderId\":\"ORD-1\",\"status\":\"REJECTED\",\"reason\":\"INSUFFICIENT_STOCK:S\",\"items\":[]}"));
        verify(writer).write(any(InventoryRow.class));

        router.route(env("metric.sample", "{\"service\":\"order-service\",\"metric\":\"cpu_pct\",\"value\":91.5,\"synthetic\":true}"));
        ArgumentCaptor<MetricRow> cap = ArgumentCaptor.forClass(MetricRow.class);
        verify(writer).write(cap.capture());
        assertThat(cap.getValue().synthetic()).isTrue();
        assertThat(cap.getValue().observedAt()).isEqualTo(cap.getValue().occurredAt());
    }

    @Test
    void duplicateIsReportedNotFailed() throws Exception {
        when(writer.write(any(OrderRow.class))).thenReturn(false);
        assertThat(router.route(env("order.created", "{\"orderId\":\"O\"}"))).isEqualTo(Outcome.DUPLICATE);
    }

    @Test
    void invalidEventsThrowForRetryAndDeadLetter() {
        assertThatThrownBy(() -> router.route(env("unknown.type", "{}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.route(env("order.created", "{}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.route(env("metric.sample", "{\"service\":\"s\",\"metric\":\"m\"}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.route("{not json")).isInstanceOf(Exception.class);
        verifyNoInteractions(writer);
    }
}
