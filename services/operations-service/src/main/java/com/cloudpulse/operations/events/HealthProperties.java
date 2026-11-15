package com.cloudpulse.operations.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloudpulse.health")
public record HealthProperties(int staleAfterSeconds, double cpuDegradedPct, double errorRateDegradedPct,
                               double latencyDegradedMs) {}
