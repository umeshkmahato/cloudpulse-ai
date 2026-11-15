package com.cloudpulse.operations.detect;

import com.cloudpulse.operations.detect.AnomalyDetector.Point;
import com.cloudpulse.operations.detect.Findings.*;
import com.cloudpulse.operations.domain.MetricSample;
import com.cloudpulse.operations.repo.MetricSampleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates detection: loads samples, learns baselines, runs the detector, drives incident lifecycle.
 * While an incident is open the baseline learned BEFORE the incident is frozen, so a long spike cannot become the new "normal".
 */
@Service
public class DetectionService {
    private static final Logger log = LoggerFactory.getLogger(DetectionService.class);

    public record CurrentState(ServiceAssessment assessment, List<Recommendation> recommendations, List<String> affectedServices) {}

    private final MetricSampleRepository samples;
    private final AnomalyDetector detector;
    private final IncidentService incidents;
    private final RecommendationEngine recommender;
    private final DependencyModel deps;
    private final DetectionProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    private final Map<String, Map<String, Baseline>> frozen = new ConcurrentHashMap<>();
    private final Map<String, CurrentState> latest = new ConcurrentHashMap<>();

    public DetectionService(MetricSampleRepository samples, AnomalyDetector detector, IncidentService incidents,
                            RecommendationEngine recommender, DependencyModel deps, DetectionProperties props, MeterRegistry meters) {
        this.samples = samples;
        this.detector = detector;
        this.incidents = incidents;
        this.recommender = recommender;
        this.deps = deps;
        this.props = props;
        this.meters = meters;
        this.clock = Clock.systemUTC();
    }

    @Scheduled(fixedDelayString = "${cloudpulse.detection.interval-ms:10000}", initialDelayString = "${cloudpulse.detection.interval-ms:10000}")
    public void scheduled() {
        if (!props.enabled()) return;
        try {
            evaluateAll();
        } catch (Exception e) {
            meters.counter("operations.detection.errors").increment();
            log.error("detection cycle failed", e);
        }
    }

    public synchronized List<CurrentState> evaluateAll() {
        Instant now = clock.instant();
        Set<String> open = incidents.openServices();
        // Services seen in the last 5x stale window (so a vanished service is reported STALE for a while, then dropped),
        // plus any service with an open incident (staleness is reported, never hidden).
        Set<String> services = new TreeSet<>(samples.servicesSince(now.minusSeconds(props.staleSeconds() * 5L)));
        services.addAll(open);
        List<CurrentState> out = new ArrayList<>();
        for (String svc : services) out.add(evaluate(svc, now, open.contains(svc)));
        latest.keySet().retainAll(services);
        meters.gauge("operations.detection.services", services.size());
        return out;
    }

    private CurrentState evaluate(String service, Instant now, boolean hasOpenIncident) {
        Instant lastSeen = lastSampleTime(service, now);
        ServiceAssessment assessment;
        if (lastSeen == null || Duration.between(lastSeen, now).getSeconds() > props.staleSeconds()) {
            assessment = new ServiceAssessment(service, Status.STALE, 0, "NONE", now, List.of(), List.of(),
                    List.of("no telemetry for more than " + props.staleSeconds() + "s: detection paused (incident, if any, stays open)"), false);
        } else {
            Map<String, Baseline> baselines = hasOpenIncident && frozen.containsKey(service) ? frozen.get(service) : learnBaselines(service, now);
            Map<String, List<Point>> recent = loadRecent(service, now);
            assessment = detector.assess(service, baselines, recent, now);
            if ((assessment.status() == Status.ANOMALY || assessment.status() == Status.FORECAST) && !frozen.containsKey(service)) {
                frozen.put(service, baselines);   // freeze the pre-incident notion of "normal"
            }
        }
        IncidentService.Change change = incidents.apply(assessment);
        if (change == IncidentService.Change.RESOLVED) frozen.remove(service);
        if (incidents.openServices().contains(service) == false) frozen.remove(service);
        List<Recommendation> recs = assessment.status() == Status.ANOMALY || assessment.status() == Status.FORECAST
                ? recommender.recommend(assessment) : List.of();
        CurrentState state = new CurrentState(assessment, recs, deps.affectedBy(service));
        latest.put(service, state);
        return state;
    }

    public List<CurrentState> current() {
        return latest.values().stream().sorted(Comparator.comparing(s -> s.assessment().service())).toList();
    }

    private Instant lastSampleTime(String service, Instant now) {
        Instant last = null;
        for (String metric : MetricRules.WEIGHTS.keySet()) {
            List<MetricSample> s = samples.findByServiceAndMetricAndObservedAtBetweenOrderByObservedAtAsc(service, metric, now.minusSeconds(props.staleSeconds() + 5L), now.plusSeconds(5));
            if (!s.isEmpty()) {
                Instant t = s.get(s.size() - 1).getObservedAt();
                if (last == null || t.isAfter(last)) last = t;
            }
        }
        return last;
    }

    private Map<String, Baseline> learnBaselines(String service, Instant now) {
        Instant to = now.minusSeconds(props.baselineGapSeconds());
        Instant from = now.minus(Duration.ofMinutes(props.baselineWindowMinutes()));
        Map<String, Baseline> out = new HashMap<>();
        for (String metric : MetricRules.WEIGHTS.keySet()) {
            List<MetricSample> rows = samples.findByServiceAndMetricAndObservedAtBetweenOrderByObservedAtAsc(service, metric, from, to);
            if (rows.size() < props.minBaselineSamples()) continue;
            double span = Duration.between(rows.get(0).getObservedAt(), rows.get(rows.size() - 1).getObservedAt()).toSeconds() / 60.0;
            if (span < props.minBaselineSpanMinutes()) continue;
            double[] v = rows.stream().mapToDouble(MetricSample::getValue).toArray();
            out.put(metric, new Baseline(Stats.mean(v), Stats.stddev(v), v.length, span));
        }
        return out;
    }

    private Map<String, List<Point>> loadRecent(String service, Instant now) {
        Instant from = now.minus(Duration.ofMinutes(props.forecastWindowMinutes()));
        Map<String, List<Point>> out = new HashMap<>();
        for (String metric : MetricRules.WEIGHTS.keySet()) {
            out.put(metric, samples.findByServiceAndMetricAndObservedAtBetweenOrderByObservedAtAsc(service, metric, from, now.plusSeconds(5))
                    .stream().map(s -> new Point(s.getObservedAt(), s.getValue(), s.isSynthetic())).toList());
        }
        return out;
    }
}
