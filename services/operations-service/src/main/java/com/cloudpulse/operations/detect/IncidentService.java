package com.cloudpulse.operations.detect;

import com.cloudpulse.operations.detect.Findings.*;
import com.cloudpulse.operations.domain.Incident;
import com.cloudpulse.operations.repo.IncidentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Incident lifecycle: at most one OPEN incident per service. DETECTED = statistically anomalous now;
 * FORECAST = trend extrapolation (low confidence). Resolves after N consecutive clean evaluations; stale or
 * insufficient data never silently resolves an incident.
 */
@Service
public class IncidentService {
    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    public record IncidentView(String id, String service, String kind, String kindExplanation, String severity, double riskScore,
                               double peakRisk, String status, Instant openedAt, Instant updatedAt, Instant resolvedAt,
                               String summary, JsonNode affectedServices, JsonNode evidence, JsonNode recommendations,
                               boolean synthetic) {}

    private final IncidentRepository repo;
    private final DetectionProperties props;
    private final RecommendationEngine recommender;
    private final DependencyModel deps;
    private final AlertPublisher alerts;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public IncidentService(IncidentRepository repo, DetectionProperties props, RecommendationEngine recommender,
                           DependencyModel deps, AlertPublisher alerts, ObjectMapper mapper, MeterRegistry meters) {
        this.repo = repo;
        this.props = props;
        this.recommender = recommender;
        this.deps = deps;
        this.alerts = alerts;
        this.mapper = mapper;
        this.meters = meters;
    }

    public enum Change { OPENED, ESCALATED, DEESCALATED, UPDATED, RESOLVED, NONE }

    @Transactional
    public Change apply(ServiceAssessment a) {
        Instant now = a.evaluatedAt();
        Optional<Incident> open = repo.findFirstByServiceAndStatus(a.service(), "OPEN");
        boolean trigger = a.status() == Status.ANOMALY || a.status() == Status.FORECAST;

        if (trigger) {
            String kind = a.status() == Status.ANOMALY ? "DETECTED" : "FORECAST";
            List<Recommendation> recs = recommender.recommend(a);
            List<String> affected = deps.affectedBy(a.service());
            String summary = summarize(a, kind);
            String evidence = json(Map.of("findings", a.findings().stream().filter(f -> f.anomalous() || a.status() == Status.ANOMALY).toList(),
                    "forecasts", a.forecasts(), "notes", a.notes(), "method", "z-score + EWMA control chart + significance gate; linear forecast"));
            if (open.isEmpty()) {
                Incident i = new Incident(UUID.randomUUID(), a.service(), kind, a.severity(), a.riskScore(), now, summary,
                        json(affected), evidence, json(recs), a.containsSyntheticData());
                repo.save(i);
                meters.counter("operations.incidents.opened", "kind", kind).increment();
                log.info("incident opened service={} kind={} risk={} severity={}", a.service(), kind, a.riskScore(), a.severity());
                alerts.publish(i, "OPENED", affected);
                return Change.OPENED;
            }
            Incident i = open.get();
            String oldSeverity = i.getSeverity(), oldKind = i.getKind();
            i.refresh(kind, a.severity(), a.riskScore(), now, summary, json(affected), evidence, json(recs), a.containsSyntheticData());
            if (!oldSeverity.equals(a.severity()) || !oldKind.equals(kind)) {
                boolean up = rank(a.severity()) > rank(oldSeverity) || (kind.equals("DETECTED") && !oldKind.equals("DETECTED"));
                alerts.publish(i, up ? "ESCALATED" : "DEESCALATED", affected);
                return up ? Change.ESCALATED : Change.DEESCALATED;
            }
            return Change.UPDATED;
        }

        if (open.isPresent() && a.status() != Status.STALE && a.status() != Status.INSUFFICIENT_DATA) {
            Incident i = open.get();
            if (i.bumpClean(now) >= props.resolveAfterCleanEvaluations()) {
                i.resolve(now);
                meters.counter("operations.incidents.resolved").increment();
                log.info("incident resolved service={} id={} peakRisk={}", i.getService(), i.getId(), i.getPeakRisk());
                alerts.publish(i, "RESOLVED", deps.affectedBy(i.getService()));
                return Change.RESOLVED;
            }
        }
        return Change.NONE;
    }

    @Transactional(readOnly = true)
    public List<IncidentView> list(String status, int limit) {
        var page = PageRequest.of(0, limit);
        var rows = status == null ? repo.findAllByOrderByOpenedAtDesc(page) : repo.findByStatusOrderByOpenedAtDesc(status, page);
        return rows.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<IncidentView> get(UUID id) {
        return repo.findById(id).map(this::view);
    }

    @Transactional(readOnly = true)
    public Set<String> openServices() {
        return repo.findAll().stream().filter(i -> i.getStatus().equals("OPEN")).map(Incident::getService).collect(Collectors.toSet());
    }

    private IncidentView view(Incident i) {
        return new IncidentView(i.getId().toString(), i.getService(), i.getKind(),
                i.getKind().equals("DETECTED") ? "Anomaly detected now: metrics are statistically unusual versus their baseline."
                        : "Forecast: a metric is trending toward saturation. Linear extrapolation, LOW confidence; not an outage.",
                i.getSeverity(), i.getRiskScore(), i.getPeakRisk(), i.getStatus(), i.getOpenedAt(), i.getUpdatedAt(), i.getResolvedAt(),
                i.getSummary(), parse(i.getAffected()), parse(i.getEvidence()), parse(i.getRecommendations()), i.isSynthetic());
    }

    private String summarize(ServiceAssessment a, String kind) {
        if (kind.equals("DETECTED")) {
            String metrics = a.anomalies().stream().map(MetricFinding::metric).collect(Collectors.joining(", "));
            return String.format("Anomaly detected on %s: %d metrics unusual (%s). Risk %.0f/100 (%s).", a.service(), a.anomalies().size(), metrics, a.riskScore(), a.severity());
        }
        Forecast f = a.forecasts().stream().min(Comparator.comparingDouble(Forecast::etaMinutes)).orElseThrow();
        return String.format("Forecast on %s: %s trending to %.0f%% in ~%.0f min if the trend continues (LOW confidence). Risk %.0f/100.", a.service(), f.metric(), f.targetPct(), f.etaMinutes(), a.riskScore());
    }

    private static int rank(String sev) { return List.of("NONE", "LOW", "MEDIUM", "HIGH", "CRITICAL").indexOf(sev); }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private JsonNode parse(String s) {
        try { return mapper.readTree(s); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
