package com.cloudpulse.operations.service;

import com.cloudpulse.operations.domain.MetricSample;
import com.cloudpulse.operations.events.EventEnvelope;
import com.cloudpulse.operations.events.MetricSampleData;
import com.cloudpulse.operations.repo.MetricSampleRepository;
import com.cloudpulse.operations.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Idempotent ingest of metric.sample events. Invalid events throw -> nack -> retries -> dead-letter topic. */
@Service
public class MetricIngestService {
    private static final Logger log = LoggerFactory.getLogger(MetricIngestService.class);

    public enum Outcome { STORED, DUPLICATE }

    private final MetricSampleRepository repo;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public MetricIngestService(MetricSampleRepository repo, ObjectMapper mapper, MeterRegistry meters) {
        this.repo = repo;
        this.mapper = mapper;
        this.meters = meters;
    }

    @Transactional
    public Outcome ingest(String json) throws JsonProcessingException {
        EventEnvelope<MetricSampleData> env = mapper.readValue(json, new TypeReference<>() {});
        if (env.correlationId() != null) MDC.put(CorrelationIdFilter.MDC_KEY, env.correlationId());
        try {
            validate(env);
            if (repo.existsByEventId(env.eventId())) {
                meters.counter("operations.metrics.duplicates").increment();
                return Outcome.DUPLICATE;
            }
            MetricSampleData d = env.data();
            Instant now = Instant.now();
            repo.save(new MetricSample(env.eventId(), d.service(), d.metric(), d.value(), d.unit(), d.synthetic(),
                    d.observedAt() != null ? d.observedAt() : env.occurredAt(), now));
            meters.counter("operations.metrics.ingested", "synthetic", String.valueOf(d.synthetic())).increment();
            log.debug("metric stored service={} metric={} value={}", d.service(), d.metric(), d.value());
            return Outcome.STORED;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void validate(EventEnvelope<MetricSampleData> env) {
        if (env.eventId() == null || env.eventId().isBlank()) throw new IllegalArgumentException("missing eventId");
        if (!"metric.sample".equals(env.eventType())) throw new IllegalArgumentException("unexpected eventType " + env.eventType());
        MetricSampleData d = env.data();
        if (d == null || d.service() == null || d.service().isBlank()) throw new IllegalArgumentException("missing service");
        if (!MetricCatalog.ALL.contains(d.metric())) throw new IllegalArgumentException("unknown metric " + d.metric());
        if (!Double.isFinite(d.value())) throw new IllegalArgumentException("non-finite value");
    }
}
