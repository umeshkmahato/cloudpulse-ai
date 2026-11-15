package com.cloudpulse.operations;

import com.cloudpulse.operations.detect.*;
import com.cloudpulse.operations.detect.Findings.*;
import com.cloudpulse.operations.domain.Incident;
import com.cloudpulse.operations.repo.IncidentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IncidentServiceTest {
    final IncidentRepository repo = mock(IncidentRepository.class);
    final AlertPublisher alerts = mock(AlertPublisher.class);
    final DetectionProperties props = new DetectionProperties(true, 10_000, 60, 180, 30, 5, 90, 3, 0.3, 3.0, 3.0, 0.2,
            8, 5, 95, 30, 0.6, 40, 3, 120);
    IncidentService service;
    Incident stored;

    @BeforeEach
    void setUp() {
        service = new IncidentService(repo, props, new RecommendationEngine(), new DependencyModel(), alerts,
                new ObjectMapper().registerModule(new JavaTimeModule()), new SimpleMeterRegistry());
        when(repo.save(any(Incident.class))).thenAnswer(i -> { stored = i.getArgument(0); return stored; });
        when(repo.findFirstByServiceAndStatus(eq("order-service"), eq("OPEN"))).thenAnswer(i -> Optional.ofNullable(stored).filter(s -> s.getStatus().equals("OPEN")));
    }

    MetricFinding f(String metric, boolean anomalous) {
        return new MetricFinding(metric, 90, 40, 2, 100, 25, 1.25, 80, 41, anomalous, anomalous ? 20 : 0, "explanation");
    }

    ServiceAssessment anomaly(double risk, String severity) {
        return new ServiceAssessment("order-service", Status.ANOMALY, risk, severity, Instant.now(),
                List.of(f("cpu_pct", true), f("latency_ms", true)), List.of(), List.of(), true);
    }

    ServiceAssessment ok() {
        return new ServiceAssessment("order-service", Status.OK, 0, "NONE", Instant.now(), List.of(f("cpu_pct", false)), List.of(), List.of(), true);
    }

    @Test
    void anomalyOpensDetectedIncidentWithRecommendationsAndPublishesAlert() {
        assertThat(service.apply(anomaly(85, "CRITICAL"))).isEqualTo(IncidentService.Change.OPENED);
        assertThat(stored.getKind()).isEqualTo("DETECTED");
        assertThat(stored.getSummary()).contains("cpu_pct").contains("85");
        assertThat(stored.getRecommendations()).contains("SCALE_DEPLOYMENT").contains("\"requiresApproval\":true");
        assertThat(stored.getAffected()).contains("inventory-service");
        verify(alerts).publish(eq(stored), eq("OPENED"), anyList());
    }

    @Test
    void repeatedAnomalyUpdatesSameIncidentWithoutNewAlertUntilSeverityChanges() {
        service.apply(anomaly(85, "CRITICAL"));
        assertThat(service.apply(anomaly(88, "CRITICAL"))).isEqualTo(IncidentService.Change.UPDATED);
        assertThat(stored.getPeakRisk()).isEqualTo(88);
        verify(repo, times(1)).save(any());                // still ONE incident
        verify(alerts, times(1)).publish(any(), any(), anyList());

        assertThat(service.apply(anomaly(65, "HIGH"))).isEqualTo(IncidentService.Change.DEESCALATED);
        assertThat(stored.getPeakRisk()).isEqualTo(88);    // peak preserved
        verify(alerts).publish(any(), eq("DEESCALATED"), anyList());
    }

    @Test
    void resolvesOnlyAfterConsecutiveCleanEvaluations() {
        service.apply(anomaly(85, "CRITICAL"));
        assertThat(service.apply(ok())).isEqualTo(IncidentService.Change.NONE);
        assertThat(service.apply(ok())).isEqualTo(IncidentService.Change.NONE);
        assertThat(stored.getStatus()).isEqualTo("OPEN");
        assertThat(service.apply(ok())).isEqualTo(IncidentService.Change.RESOLVED);   // 3rd clean evaluation
        assertThat(stored.getStatus()).isEqualTo("RESOLVED");
        verify(alerts).publish(any(), eq("RESOLVED"), anyList());
    }

    @Test
    void anomalyInterruptingCleanStreakResetsIt() {
        service.apply(anomaly(85, "CRITICAL"));
        service.apply(ok());
        service.apply(ok());
        service.apply(anomaly(85, "CRITICAL"));
        assertThat(service.apply(ok())).isEqualTo(IncidentService.Change.NONE);
        assertThat(stored.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void staleOrInsufficientDataNeverSilentlyResolvesAnIncident() {
        service.apply(anomaly(85, "CRITICAL"));
        for (int i = 0; i < 10; i++) {
            service.apply(new ServiceAssessment("order-service", Status.STALE, 0, "NONE", Instant.now(), List.of(), List.of(), List.of("stale"), false));
            service.apply(new ServiceAssessment("order-service", Status.INSUFFICIENT_DATA, 0, "NONE", Instant.now(), List.of(), List.of(), List.of("x"), false));
        }
        assertThat(stored.getStatus()).isEqualTo("OPEN");
    }

    @Test
    void forecastOpensForecastKindAndEscalatesWhenAnomalyAppears() {
        var forecast = new Forecast("cpu_pct", 60, 4, 0.9, 30, 95, 9, "LOW", "LOW confidence ...");
        var fa = new ServiceAssessment("order-service", Status.FORECAST, 55, "MEDIUM", Instant.now(), List.of(f("cpu_pct", false)), List.of(forecast), List.of(), true);
        assertThat(service.apply(fa)).isEqualTo(IncidentService.Change.OPENED);
        assertThat(stored.getKind()).isEqualTo("FORECAST");
        assertThat(stored.getSummary()).contains("LOW confidence").contains("~9 min");

        assertThat(service.apply(anomaly(85, "CRITICAL"))).isEqualTo(IncidentService.Change.ESCALATED);
        assertThat(stored.getKind()).isEqualTo("DETECTED");
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        verify(alerts, times(2)).publish(any(), state.capture(), anyList());
        assertThat(state.getAllValues()).containsExactly("OPENED", "ESCALATED");
    }
}
