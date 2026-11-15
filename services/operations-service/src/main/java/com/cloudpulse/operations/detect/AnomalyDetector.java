package com.cloudpulse.operations.detect;

import com.cloudpulse.operations.detect.Findings.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transparent statistical detector (no ML):
 *  1. z-score of the current level against the learned baseline (mean, std),
 *  2. EWMA control chart (is the smoothed level above mean + L*sigma*sqrt(a/(2-a))?),
 *  3. practical-significance gate (at least +X% above baseline),
 * a metric is ANOMALOUS only when all three hold. Risk = weighted sum of anomalous metrics' contributions.
 * Separately, saturating metrics get a linear time-to-saturation FORECAST. A forecast is NOT a detection.
 * Pure function of its inputs: no I/O, fully unit-testable.
 */
@Component
public class AnomalyDetector {
    public record Point(Instant time, double value, boolean synthetic) {}

    private final DetectionProperties p;

    public AnomalyDetector(DetectionProperties p) {
        this.p = p;
    }

    public ServiceAssessment assess(String service, Map<String, Baseline> baselines, Map<String, List<Point>> recent, Instant now) {
        List<MetricFinding> findings = new ArrayList<>();
        List<Forecast> forecasts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean synthetic = false;
        double risk = 0;

        for (var entry : MetricRules.WEIGHTS.entrySet()) {
            String metric = entry.getKey();
            double weight = entry.getValue();
            Baseline base = baselines.get(metric);
            List<Point> pts = recent.getOrDefault(metric, List.of());
            if (base == null) { notes.add(metric + ": no usable baseline (need >= " + p.minBaselineSamples() + " samples over >= " + p.minBaselineSpanMinutes() + " min)"); continue; }
            List<Point> fresh = pts.stream().filter(x -> Duration.between(x.time(), now).getSeconds() <= p.currentWindowSeconds()).toList();
            if (fresh.size() < p.currentSamples()) { notes.add(metric + ": only " + fresh.size() + " fresh samples (need " + p.currentSamples() + ")"); continue; }
            synthetic |= pts.stream().anyMatch(Point::synthetic);

            double[] last = fresh.subList(fresh.size() - p.currentSamples(), fresh.size()).stream().mapToDouble(Point::value).toArray();
            double current = Stats.mean(last);
            double floor = MetricRules.ABS_FLOOR.get(metric);
            double sigma = Math.max(Math.max(base.std(), 0.02 * Math.abs(base.mean())), floor);
            double z = (current - base.mean()) / sigma;
            double rel = (current - base.mean()) / Math.max(Math.abs(base.mean()), floor);
            double[] series = pts.stream().skip(Math.max(0, pts.size() - 20)).mapToDouble(Point::value).toArray();
            double ewma = Stats.ewma(series, p.ewmaAlpha(), base.mean());
            double ucl = base.mean() + p.ewmaL() * sigma * Math.sqrt(p.ewmaAlpha() / (2 - p.ewmaAlpha()));

            boolean anomalous = z >= p.zThreshold() && rel >= p.minRelativeDeviation() && ewma > ucl;
            double contribution = anomalous ? weight * Stats.clamp(0.4 + 0.6 * rel, 0.4, 1.0) : 0;
            risk += contribution;
            findings.add(new MetricFinding(metric, round(current), round(base.mean()), round(base.std()), base.samples(),
                    round(z), round(rel), round(ewma), round(ucl), anomalous, round(100 * contribution), explain(metric, current, base, z, rel, ewma, ucl, anomalous)));

            if (MetricRules.SATURATING.contains(metric)) forecast(metric, pts, current, now).ifPresent(forecasts::add);
        }

        double riskScore = Math.round(Stats.clamp(risk * 100, 0, 100));
        if (findings.isEmpty()) {
            return new ServiceAssessment(service, Status.INSUFFICIENT_DATA, 0, "NONE", now, findings, forecasts, notes, synthetic);
        }
        Status status = riskScore >= p.incidentMinRisk() ? Status.ANOMALY
                : !forecasts.isEmpty() ? Status.FORECAST
                : riskScore >= 20 ? Status.WATCH : Status.OK;
        if (status == Status.FORECAST) {
            double eta = forecasts.stream().mapToDouble(Forecast::etaMinutes).min().orElse(p.forecastHorizonMinutes());
            riskScore = Math.max(riskScore, Math.round(30 + 30 * (1 - Stats.clamp(eta / p.forecastHorizonMinutes(), 0, 1))));
        }
        return new ServiceAssessment(service, status, riskScore, severity(riskScore, status), now, findings, forecasts, notes, synthetic);
    }

    public static String severity(double risk, Status status) {
        if (status == Status.OK || status == Status.INSUFFICIENT_DATA || status == Status.STALE) return "NONE";
        return risk >= 80 ? "CRITICAL" : risk >= 60 ? "HIGH" : risk >= 40 ? "MEDIUM" : "LOW";
    }

    private java.util.Optional<Forecast> forecast(String metric, List<Point> pts, double current, Instant now) {
        List<Point> window = pts.stream().filter(x -> Duration.between(x.time(), now).toMinutes() <= p.forecastWindowMinutes()).toList();
        if (window.size() < p.forecastMinSamples()) return java.util.Optional.empty();
        double t0 = window.get(0).time().toEpochMilli();
        double[] x = window.stream().mapToDouble(pt -> (pt.time().toEpochMilli() - t0) / 60_000.0).toArray();
        double[] y = window.stream().mapToDouble(Point::value).toArray();
        Stats.Line line = Stats.regress(x, y);
        if (line.slope() <= 0 || line.r2() < p.forecastMinR2() || current >= p.forecastTargetPct()) return java.util.Optional.empty();
        double eta = (p.forecastTargetPct() - current) / line.slope();
        if (eta > p.forecastHorizonMinutes()) return java.util.Optional.empty();
        String expl = String.format("%s rising %.2f/min (r²=%.2f over %d samples); at this rate it reaches %.0f%% in ~%.0f min. "
                + "Linear extrapolation only: LOW confidence, not a trained prediction.", metric, line.slope(), line.r2(), window.size(), p.forecastTargetPct(), eta);
        return java.util.Optional.of(new Forecast(metric, round(current), round(line.slope()), round(line.r2()), window.size(),
                p.forecastTargetPct(), round(eta), "LOW", expl));
    }

    private String explain(String metric, double current, Baseline b, double z, double rel, double ewma, double ucl, boolean anomalous) {
        if (anomalous) {
            return String.format("%s is %.1f (baseline %.1f ± %.1f over %d samples): z=%.1f ≥ %.1f, +%.0f%% vs baseline, EWMA %.1f > control limit %.1f.",
                    metric, current, b.mean(), b.std(), b.samples(), z, p.zThreshold(), rel * 100, ewma, ucl);
        }
        return String.format("%s is %.1f, within normal range (baseline %.1f ± %.1f; z=%.1f).", metric, current, b.mean(), b.std(), z);
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
