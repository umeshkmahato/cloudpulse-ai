package com.cloudpulse.operations.detect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All thresholds are explicit and documented in architecture/anomaly-detection.md. */
@ConfigurationProperties(prefix = "cloudpulse.detection")
public record DetectionProperties(
        boolean enabled,
        long intervalMs,
        int baselineWindowMinutes,      // history used to learn "normal"
        int baselineGapSeconds,         // newest data excluded from the baseline so an ongoing spike cannot contaminate it
        int minBaselineSamples,
        int minBaselineSpanMinutes,
        int currentWindowSeconds,       // "now" = mean of the last few samples inside this window
        int currentSamples,
        double ewmaAlpha,
        double ewmaL,                   // control-limit width in sigmas
        double zThreshold,
        double minRelativeDeviation,    // practical significance: must also be this far above baseline (0.2 = +20%)
        int forecastMinSamples,
        int forecastWindowMinutes,
        double forecastTargetPct,
        int forecastHorizonMinutes,
        double forecastMinR2,
        double incidentMinRisk,
        int resolveAfterCleanEvaluations,
        int staleSeconds) {}
