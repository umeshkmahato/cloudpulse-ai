package com.cloudpulse.operations;

import com.cloudpulse.operations.domain.MetricSample;
import com.cloudpulse.operations.events.HealthProperties;
import com.cloudpulse.operations.repo.MetricSampleRepository;
import com.cloudpulse.operations.service.MetricIngestService;
import com.cloudpulse.operations.service.MetricIngestService.Outcome;
import com.cloudpulse.operations.service.ServiceHealthEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationsServiceTest {
    private final MetricSampleRepository repo = mock(MetricSampleRepository.class);
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MetricIngestService ingest;

    @BeforeEach
    void setUp() {
        ingest = new MetricIngestService(repo, mapper, new SimpleMeterRegistry());
    }

    private String event(String id, String type, String metric, String value) {
        return """
            {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z","correlationId":"c1",
             "source":"test","data":{"service":"order-service","metric":"%s","value":%s,"unit":"pct","synthetic":true}}
            """.formatted(id, type, metric, value);
    }

    @Test
    void storesValidSampleAndKeepsSyntheticLabel() throws Exception {
        assertThat(ingest.ingest(event("e1", "metric.sample", "cpu_pct", "42.5"))).isEqualTo(Outcome.STORED);
        verify(repo).save(any(MetricSample.class));
    }

    @Test
    void duplicateEventIdIsNotStoredTwice() throws Exception {
        when(repo.existsByEventId("e1")).thenReturn(true);
        assertThat(ingest.ingest(event("e1", "metric.sample", "cpu_pct", "1"))).isEqualTo(Outcome.DUPLICATE);
        verify(repo, never()).save(any());
    }

    @Test
    void unknownMetricOrWrongTypeIsRejectedForDeadLettering() {
        assertThatThrownBy(() -> ingest.ingest(event("e2", "metric.sample", "bogus", "1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest.ingest(event("e3", "other.type", "cpu_pct", "1"))).isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void healthIsDegradedWithExplanationStaleOrUp() {
        Instant now = Instant.parse("2026-01-01T00:10:00Z");
        var eval = new ServiceHealthEvaluator(new HealthProperties(120, 85, 5, 1000), Clock.fixed(now, ZoneOffset.UTC));
        var hot = new MetricSample("a", "order-service", "cpu_pct", 92, "pct", true, now.minusSeconds(10), now);
        var slow = new MetricSample("b", "order-service", "latency_ms", 2500, "ms", true, now.minusSeconds(10), now);
        var ok = new MetricSample("c", "inventory-service", "cpu_pct", 30, "pct", true, now.minusSeconds(10), now);
        var old = new MetricSample("d", "ops", "cpu_pct", 30, "pct", false, now.minusSeconds(600), now);

        var result = eval.evaluate(List.of(hot, slow, ok, old));

        assertThat(result).extracting("service", "status").containsExactly(
                org.assertj.core.groups.Tuple.tuple("inventory-service", "UP"),
                org.assertj.core.groups.Tuple.tuple("ops", "STALE"),
                org.assertj.core.groups.Tuple.tuple("order-service", "DEGRADED"));
        assertThat(result.get(2).reasons()).hasSize(2);
        assertThat(result.get(2).containsSyntheticData()).isTrue();
    }
}
