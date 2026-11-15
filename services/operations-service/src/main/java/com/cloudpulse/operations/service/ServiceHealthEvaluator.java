package com.cloudpulse.operations.service;

import com.cloudpulse.operations.domain.MetricSample;
import com.cloudpulse.operations.events.HealthProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Rule-based service health from the latest sample per metric (static thresholds, fully explainable).
 * This is NOT anomaly detection; baselines/EWMA arrive in Phase 9.
 */
@Component
public class ServiceHealthEvaluator {
    public record ServiceHealth(String service, String status, List<String> reasons, Instant lastSampleAt,
                                boolean containsSyntheticData) {}

    private final HealthProperties props;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ServiceHealthEvaluator(HealthProperties props) {
        this(props, Clock.systemUTC());
    }

    public ServiceHealthEvaluator(HealthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public List<ServiceHealth> evaluate(List<MetricSample> latest) {
        Map<String, List<MetricSample>> byService = new TreeMap<>();
        latest.forEach(s -> byService.computeIfAbsent(s.getService(), k -> new ArrayList<>()).add(s));
        List<ServiceHealth> out = new ArrayList<>();
        Instant now = clock.instant();
        byService.forEach((svc, samples) -> {
            Instant last = samples.stream().map(MetricSample::getObservedAt).max(Comparator.naturalOrder()).orElseThrow();
            boolean synthetic = samples.stream().anyMatch(MetricSample::isSynthetic);
            if (Duration.between(last, now).getSeconds() > props.staleAfterSeconds()) {
                out.add(new ServiceHealth(svc, "STALE", List.of("no telemetry for more than " + props.staleAfterSeconds() + "s"), last, synthetic));
                return;
            }
            List<String> reasons = new ArrayList<>();
            for (MetricSample s : samples) {
                switch (s.getMetric()) {
                    case MetricCatalog.CPU_PCT -> { if (s.getValue() > props.cpuDegradedPct()) reasons.add("cpu_pct " + s.getValue() + " > " + props.cpuDegradedPct()); }
                    case MetricCatalog.ERROR_RATE_PCT -> { if (s.getValue() > props.errorRateDegradedPct()) reasons.add("error_rate_pct " + s.getValue() + " > " + props.errorRateDegradedPct()); }
                    case MetricCatalog.LATENCY_MS -> { if (s.getValue() > props.latencyDegradedMs()) reasons.add("latency_ms " + s.getValue() + " > " + props.latencyDegradedMs()); }
                    default -> { }
                }
            }
            out.add(new ServiceHealth(svc, reasons.isEmpty() ? "UP" : "DEGRADED", reasons, last, synthetic));
        });
        return out;
    }
}
