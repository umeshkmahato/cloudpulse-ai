package com.cloudpulse.operations.detect;

import com.cloudpulse.operations.service.MetricCatalog;

import java.util.Map;
import java.util.Set;

/** Per-metric configuration: how much each signal contributes to the risk score and its noise floor. */
public final class MetricRules {
    /** Weights sum to 1.0. Latency and errors are customer-facing, so they weigh most. */
    public static final Map<String, Double> WEIGHTS = Map.of(
            MetricCatalog.LATENCY_MS, 0.25,
            MetricCatalog.ERROR_RATE_PCT, 0.20,
            MetricCatalog.CPU_PCT, 0.20,
            MetricCatalog.DB_CONNECTIONS_PCT, 0.15,
            MetricCatalog.REQUEST_COUNT, 0.05,
            MetricCatalog.MEMORY_PCT, 0.05,
            MetricCatalog.EVENT_LATENCY_MS, 0.05,
            MetricCatalog.POD_RESTARTS, 0.05);

    /** Minimum meaningful sigma / scale per metric (in the metric's unit) so near-constant series cannot yield absurd z-scores. */
    public static final Map<String, Double> ABS_FLOOR = Map.of(
            MetricCatalog.LATENCY_MS, 15.0,
            MetricCatalog.ERROR_RATE_PCT, 0.3,
            MetricCatalog.CPU_PCT, 2.0,
            MetricCatalog.DB_CONNECTIONS_PCT, 2.0,
            MetricCatalog.REQUEST_COUNT, 5.0,
            MetricCatalog.MEMORY_PCT, 1.5,
            MetricCatalog.EVENT_LATENCY_MS, 15.0,
            MetricCatalog.POD_RESTARTS, 0.5);

    /** Metrics with a hard 100% ceiling: candidates for time-to-saturation forecasting. */
    public static final Set<String> SATURATING = Set.of(MetricCatalog.CPU_PCT, MetricCatalog.MEMORY_PCT, MetricCatalog.DB_CONNECTIONS_PCT);

    private MetricRules() {}
}
