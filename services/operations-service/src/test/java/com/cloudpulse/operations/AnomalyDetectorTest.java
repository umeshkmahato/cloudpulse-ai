package com.cloudpulse.operations;

import com.cloudpulse.operations.detect.AnomalyDetector;
import com.cloudpulse.operations.detect.AnomalyDetector.Point;
import com.cloudpulse.operations.detect.DetectionProperties;
import com.cloudpulse.operations.detect.Findings.*;
import com.cloudpulse.operations.detect.Stats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.function.IntToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {
    static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    final DetectionProperties props = new DetectionProperties(true, 10_000, 60, 180, 30, 5, 90, 3, 0.3, 3.0, 3.0, 0.2,
            8, 5, 95, 30, 0.6, 40, 6, 120);
    final AnomalyDetector detector = new AnomalyDetector(props);

    static final Map<String, double[]> NORMAL = Map.of( // {mean, std}
            "request_count", new double[]{100, 8}, "latency_ms", new double[]{300, 30}, "cpu_pct", new double[]{40, 2},
            "memory_pct", new double[]{55, 1.1}, "db_connections_pct", new double[]{50, 2}, "error_rate_pct", new double[]{0.5, 0.1},
            "event_latency_ms", new double[]{120, 12}, "pod_restarts", new double[]{0, 0});

    Map<String, Baseline> baselines() {
        Map<String, Baseline> b = new HashMap<>();
        NORMAL.forEach((m, v) -> b.put(m, new Baseline(v[0], v[1], 200, 30)));
        return b;
    }

    /** n samples, 5 s apart, ending at NOW; value(i) for i in 0..n-1 (n-1 is the newest). */
    List<Point> series(int n, IntToDoubleFunction value) {
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Point(NOW.minusSeconds(5L * (n - 1 - i)), value.applyAsDouble(i), true));
        return out;
    }

    Map<String, List<Point>> recentAllNormal() {
        Map<String, List<Point>> r = new HashMap<>();
        NORMAL.forEach((m, v) -> r.put(m, series(40, i -> v[0] + v[1] * Math.sin(i * 1.7) * 0.8)));
        return r;
    }

    @Test
    void normalNoiseProducesNoAnomalyAndLowRisk() {
        ServiceAssessment a = detector.assess("order-service", baselines(), recentAllNormal(), NOW);
        assertThat(a.status()).isEqualTo(Status.OK);
        assertThat(a.anomalies()).isEmpty();
        assertThat(a.riskScore()).isZero();
        assertThat(a.severity()).isEqualTo("NONE");
    }

    @Test
    void overloadRampIsDetectedWithExplainableEvidence() {
        Map<String, List<Point>> r = recentAllNormal();
        r.put("request_count", series(40, i -> 100 + 900.0 * i / 39));
        r.put("latency_ms", series(40, i -> 300 + 2200.0 * i / 39));
        r.put("cpu_pct", series(40, i -> 40 + 50.0 * i / 39));
        r.put("db_connections_pct", series(40, i -> 50 + 45.0 * i / 39));
        r.put("error_rate_pct", series(40, i -> 0.5 + 8.5 * i / 39));

        ServiceAssessment a = detector.assess("order-service", baselines(), r, NOW);

        assertThat(a.status()).isEqualTo(Status.ANOMALY);
        assertThat(a.riskScore()).isGreaterThanOrEqualTo(80);
        assertThat(a.severity()).isEqualTo("CRITICAL");
        assertThat(a.anomalies()).extracting(MetricFinding::metric)
                .contains("cpu_pct", "latency_ms", "db_connections_pct", "error_rate_pct", "request_count");
        MetricFinding cpu = a.findings().stream().filter(f -> f.metric().equals("cpu_pct")).findFirst().orElseThrow();
        assertThat(cpu.zScore()).isGreaterThan(3);
        assertThat(cpu.explanation()).contains("z=").contains("baseline").contains("control limit");
        assertThat(a.containsSyntheticData()).isTrue();
    }

    @Test
    void smallBlipBelowSignificanceThresholdsIsNotAnAnomaly() {
        Map<String, List<Point>> r = recentAllNormal();
        r.put("cpu_pct", series(40, i -> 40 + (i > 35 ? 7 : 0)));   // +17%: z>3 vs sigma 2 but under the +20% gate
        ServiceAssessment a = detector.assess("order-service", baselines(), r, NOW);
        assertThat(a.anomalies()).isEmpty();
        assertThat(a.status()).isNotEqualTo(Status.ANOMALY);
    }

    @Test
    void nearConstantSeriesDoesNotProduceAbsurdZScores() {
        Map<String, Baseline> b = baselines();
        b.put("memory_pct", new Baseline(55, 0.0, 200, 30));        // zero variance baseline
        Map<String, List<Point>> r = recentAllNormal();
        r.put("memory_pct", series(40, i -> 55.8));                  // +1.5%
        ServiceAssessment a = detector.assess("s", b, r, NOW);
        MetricFinding mem = a.findings().stream().filter(f -> f.metric().equals("memory_pct")).findFirst().orElseThrow();
        assertThat(mem.zScore()).isLessThan(1.5);
        assertThat(mem.anomalous()).isFalse();
    }

    @Test
    void noBaselineMeansInsufficientDataNotAGuess() {
        ServiceAssessment a = detector.assess("new-service", Map.of(), recentAllNormal(), NOW);
        assertThat(a.status()).isEqualTo(Status.INSUFFICIENT_DATA);
        assertThat(a.riskScore()).isZero();
        assertThat(a.notes()).isNotEmpty().anyMatch(n -> n.contains("no usable baseline"));
    }

    @Test
    void tooFewFreshSamplesAreReportedAndIgnored() {
        Map<String, List<Point>> r = new HashMap<>();
        NORMAL.forEach((m, v) -> r.put(m, series(2, i -> v[0])));
        ServiceAssessment a = detector.assess("s", baselines(), r, NOW);
        assertThat(a.status()).isEqualTo(Status.INSUFFICIENT_DATA);
        assertThat(a.notes()).anyMatch(n -> n.contains("fresh samples"));
    }

    @Test
    void steadyClimbTowardCeilingIsForecastNotDetected() {
        Map<String, Baseline> b = baselines();
        b.put("cpu_pct", new Baseline(40, 8, 200, 30));              // noisy baseline: 40 ± 8
        Map<String, List<Point>> r = recentAllNormal();
        r.put("cpu_pct", series(40, i -> 40 + 20.0 * i / 39));       // climbs 40 -> 60 in ~3.3 min (z=2.5, below 3)

        ServiceAssessment a = detector.assess("order-service", b, r, NOW);

        assertThat(a.anomalies()).isEmpty();
        assertThat(a.status()).isEqualTo(Status.FORECAST);
        Forecast f = a.forecasts().get(0);
        assertThat(f.metric()).isEqualTo("cpu_pct");
        assertThat(f.confidence()).isEqualTo("LOW");
        assertThat(f.etaMinutes()).isBetween(1.0, 30.0);
        assertThat(f.explanation()).contains("LOW confidence").contains("not a trained prediction");
    }

    @Test
    void fallingOrNoisyTrendsAreNotForecast() {
        Map<String, Baseline> b = baselines();
        b.put("cpu_pct", new Baseline(40, 8, 200, 30));
        Map<String, List<Point>> falling = recentAllNormal();
        falling.put("cpu_pct", series(40, i -> 60 - 20.0 * i / 39));
        assertThat(detector.assess("s", b, falling, NOW).forecasts()).isEmpty();

        Random rnd = new Random(7);
        Map<String, List<Point>> noisy = recentAllNormal();
        noisy.put("cpu_pct", series(40, i -> 40 + rnd.nextGaussian() * 6));
        assertThat(detector.assess("s", b, noisy, NOW).forecasts()).isEmpty();
    }

    @Test
    void statsAreCorrect() {
        assertThat(Stats.mean(new double[]{1, 2, 3, 4})).isEqualTo(2.5);
        assertThat(Stats.stddev(new double[]{2, 4, 4, 4, 5, 5, 7, 9})).isBetween(2.13, 2.14);   // sample std
        assertThat(Stats.ewma(new double[]{10, 10, 10}, 0.5, 0)).isEqualTo(8.75);
        Stats.Line l = Stats.regress(new double[]{0, 1, 2, 3}, new double[]{1, 3, 5, 7});
        assertThat(l.slope()).isEqualTo(2.0);
        assertThat(l.r2()).isEqualTo(1.0);
    }
}
