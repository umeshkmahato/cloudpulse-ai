package com.cloudpulse.operations.detect;

import java.time.Instant;
import java.util.List;

/** Immutable, serialisable results. Every number needed to reproduce a decision is included (explainability). */
public final class Findings {

    /** The learned notion of "normal" for one (service, metric). */
    public record Baseline(double mean, double std, int samples, double spanMinutes) {}

    /** One metric's comparison of "now" against its baseline. */
    public record MetricFinding(String metric, double current, double baselineMean, double baselineStd, int baselineSamples,
                                double zScore, double relativeDeviation, double ewma, double ewmaUpperControlLimit,
                                boolean anomalous, double riskContribution, String explanation) {}

    /** Linear-extrapolation forecast. LOW confidence by construction: a trend line, not a trained model. */
    public record Forecast(String metric, double current, double slopePerMinute, double r2, int samples,
                           double targetPct, double etaMinutes, String confidence, String explanation) {}

    public record Recommendation(String actionId, String title, String rationale, boolean requiresApproval) {}

    public enum Status { OK, WATCH, ANOMALY, FORECAST, INSUFFICIENT_DATA, STALE }

    public record ServiceAssessment(String service, Status status, double riskScore, String severity, Instant evaluatedAt,
                                    List<MetricFinding> findings, List<Forecast> forecasts, List<String> notes,
                                    boolean containsSyntheticData) {
        public List<MetricFinding> anomalies() { return findings.stream().filter(MetricFinding::anomalous).toList(); }
    }

    private Findings() {}
}
