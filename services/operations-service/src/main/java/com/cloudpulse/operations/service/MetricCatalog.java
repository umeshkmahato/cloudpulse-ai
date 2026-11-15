package com.cloudpulse.operations.service;

import java.util.Set;

/** Canonical metric names accepted by the platform (keeps dashboards and detectors consistent). */
public final class MetricCatalog {
    public static final String REQUEST_COUNT = "request_count";
    public static final String LATENCY_MS = "latency_ms";
    public static final String CPU_PCT = "cpu_pct";
    public static final String MEMORY_PCT = "memory_pct";
    public static final String POD_RESTARTS = "pod_restarts";
    public static final String DB_CONNECTIONS_PCT = "db_connections_pct";
    public static final String ERROR_RATE_PCT = "error_rate_pct";
    public static final String EVENT_LATENCY_MS = "event_latency_ms";
    public static final String BUSINESS_TRANSACTIONS = "business_transactions";

    public static final Set<String> ALL = Set.of(REQUEST_COUNT, LATENCY_MS, CPU_PCT, MEMORY_PCT, POD_RESTARTS,
            DB_CONNECTIONS_PCT, ERROR_RATE_PCT, EVENT_LATENCY_MS, BUSINESS_TRANSACTIONS);

    private MetricCatalog() {}
}
