package com.cloudpulse.operations.repo;

import com.cloudpulse.operations.domain.MetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface MetricSampleRepository extends JpaRepository<MetricSample, Long> {
    boolean existsByEventId(String eventId);

    List<MetricSample> findByServiceAndMetricAndObservedAtBetweenOrderByObservedAtAsc(String service, String metric, Instant from, Instant to);

    @Query("select distinct m.service from MetricSample m where m.observedAt > :since")
    List<String> servicesSince(Instant since);

    List<MetricSample> findByServiceAndMetricAndObservedAtAfterOrderByObservedAtAsc(String service, String metric, Instant after);

    /** Most recent sample per (service, metric). */
    @Query("""
            select m from MetricSample m
            where m.observedAt = (select max(m2.observedAt) from MetricSample m2
                                  where m2.service = m.service and m2.metric = m.metric)
            order by m.service, m.metric""")
    List<MetricSample> latestPerSeries();
}
