package com.cloudpulse.operations.detect;

import com.cloudpulse.operations.detect.Findings.MetricFinding;
import com.cloudpulse.operations.detect.Findings.Recommendation;
import com.cloudpulse.operations.detect.Findings.ServiceAssessment;
import com.cloudpulse.operations.service.MetricCatalog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rule-based recommendations mapped to a PREDEFINED action catalogue (the same ids the Phase 12 remediation controller
 * will allow). Nothing here executes anything; every action requires human approval or an explicit policy.
 */
@Component
public class RecommendationEngine {
    public static final String SCALE_OUT = "SCALE_DEPLOYMENT";
    public static final String INSPECT = "INSPECT_ONLY";

    public List<Recommendation> recommend(ServiceAssessment a) {
        Set<String> hot = a.anomalies().stream().map(MetricFinding::metric).collect(Collectors.toSet());
        a.forecasts().forEach(f -> hot.add(f.metric()));
        List<Recommendation> out = new ArrayList<>();

        boolean cpu = hot.contains(MetricCatalog.CPU_PCT), db = hot.contains(MetricCatalog.DB_CONNECTIONS_PCT);
        boolean lat = hot.contains(MetricCatalog.LATENCY_MS), err = hot.contains(MetricCatalog.ERROR_RATE_PCT);

        if (cpu || (lat && hot.contains(MetricCatalog.REQUEST_COUNT))) {
            out.add(new Recommendation(SCALE_OUT, "Scale out " + a.service(),
                    "CPU saturation / load growth detected. Add replicas (bounded by the HPA maximum) to spread load.", true));
        }
        if (db) {
            out.add(new Recommendation(INSPECT, "Check database connection pool before scaling further",
                    "DB connections are near the limit. More replicas multiply connections: confirm pool size and DB capacity first, otherwise scaling can worsen the incident.", true));
        }
        if (err) {
            out.add(new Recommendation(INSPECT, "Investigate error-rate increase",
                    "Error rate is above baseline: check recent deployments, dependency health (inventory/DB) and DLQ backlog.", true));
        }
        if (hot.contains(MetricCatalog.EVENT_LATENCY_MS)) {
            out.add(new Recommendation(INSPECT, "Check consumer backlog",
                    "Event processing latency is up: inspect oldest unacked message age and consumer throughput.", true));
        }
        if (hot.contains(MetricCatalog.POD_RESTARTS) || hot.contains(MetricCatalog.MEMORY_PCT)) {
            out.add(new Recommendation(INSPECT, "Inspect restarts / memory pressure",
                    "Restarts or memory growth suggest OOM kills or crash loops: review memory limits and recent logs.", true));
        }
        if (out.isEmpty() && !hot.isEmpty()) {
            out.add(new Recommendation(INSPECT, "Review metrics manually", "Anomalous signals without a standard playbook.", true));
        }
        return out;
    }
}
