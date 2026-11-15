package com.cloudpulse.operations.api;

import com.cloudpulse.operations.domain.MetricSample;
import com.cloudpulse.operations.events.EventEnvelope;
import com.cloudpulse.operations.events.EventPublisher;
import com.cloudpulse.operations.events.MetricSampleData;
import com.cloudpulse.operations.events.PubSubProperties;
import com.cloudpulse.operations.repo.MetricSampleRepository;
import com.cloudpulse.operations.service.MetricCatalog;
import com.cloudpulse.operations.service.ServiceHealthEvaluator;
import com.cloudpulse.operations.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Operations")
@Validated
public class TelemetryController {
    public record MetricRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String service,
            @NotBlank String metric,
            @NotNull Double value,
            @Size(max = 16) String unit,
            Boolean synthetic,
            Instant observedAt) {}

    public record SampleView(String service, String metric, double value, String unit, boolean synthetic, Instant observedAt) {
        static SampleView of(MetricSample s) {
            return new SampleView(s.getService(), s.getMetric(), s.getValue(), s.getUnit(), s.isSynthetic(), s.getObservedAt());
        }
    }

    private final MetricSampleRepository repo;
    private final EventPublisher publisher;
    private final PubSubProperties props;
    private final ObjectMapper mapper;
    private final ServiceHealthEvaluator health;

    public TelemetryController(MetricSampleRepository repo, EventPublisher publisher, PubSubProperties props,
                               ObjectMapper mapper, ServiceHealthEvaluator health) {
        this.repo = repo;
        this.publisher = publisher;
        this.props = props;
        this.mapper = mapper;
        this.health = health;
    }

    @PostMapping("/telemetry/metrics")
    @Operation(summary = "Publish a metric sample to Pub/Sub (application-metrics). synthetic defaults to TRUE: test data must be labelled.")
    public ResponseEntity<Map<String, String>> ingest(@Valid @RequestBody MetricRequest req) throws Exception {
        if (!MetricCatalog.ALL.contains(req.metric())) {
            throw new IllegalArgumentException("Unknown metric '" + req.metric() + "'. Allowed: " + new java.util.TreeSet<>(MetricCatalog.ALL));
        }
        Instant now = Instant.now();
        String eventId = UUID.randomUUID().toString();
        String corr = MDC.get(CorrelationIdFilter.MDC_KEY);
        var data = new MetricSampleData(req.service(), req.metric(), req.value(), req.unit(),
                req.synthetic() == null || req.synthetic(), req.observedAt() != null ? req.observedAt() : now);
        var env = new EventEnvelope<>(eventId, "metric.sample", 1, now, corr, EventEnvelope.SOURCE, data);
        publisher.publish(props.metricsTopic(), mapper.writeValueAsString(env),
                Map.of("eventId", eventId, "eventType", "metric.sample", "correlationId", corr));
        return ResponseEntity.accepted().body(Map.of("eventId", eventId, "status", "ACCEPTED"));
    }

    @GetMapping("/metrics/latest")
    @Operation(summary = "Latest sample for every (service, metric)")
    public List<SampleView> latest() {
        return repo.latestPerSeries().stream().map(SampleView::of).toList();
    }

    @GetMapping("/metrics/series")
    @Operation(summary = "Time series for one service/metric")
    public List<SampleView> series(@RequestParam @NotBlank String service, @RequestParam @NotBlank String metric,
                                   @RequestParam(defaultValue = "15") @Min(1) @Max(1440) int minutes) {
        return repo.findByServiceAndMetricAndObservedAtAfterOrderByObservedAtAsc(service, metric,
                Instant.now().minus(Duration.ofMinutes(minutes))).stream().map(SampleView::of).toList();
    }

    @GetMapping("/services/health")
    @Operation(summary = "Rule-based service health (static thresholds; not anomaly detection)")
    public List<ServiceHealthEvaluator.ServiceHealth> serviceHealth() {
        return health.evaluate(repo.latestPerSeries());
    }
}
