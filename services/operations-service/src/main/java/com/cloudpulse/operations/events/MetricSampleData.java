package com.cloudpulse.operations.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** metric.sample v1 (topic application-metrics). synthetic=true marks generated test data. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricSampleData(String service, String metric, double value, String unit, boolean synthetic,
                               Instant observedAt) {}
