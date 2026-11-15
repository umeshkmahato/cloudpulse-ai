package com.cloudpulse.inventory.events;

import com.cloudpulse.inventory.domain.OutboxEvent;
import com.cloudpulse.inventory.repo.OutboxRepository;
import com.cloudpulse.inventory.web.CorrelationIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Relays committed outbox rows to Pub/Sub (at-least-once; consumers are idempotent on eventId).
 * Failed rows stay unpublished and are retried on the next poll, so a broker outage loses nothing.
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repo;
    private final EventPublisher publisher;
    private final int batchSize;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(OutboxRepository repo, EventPublisher publisher, MeterRegistry meters,
                       @Value("${cloudpulse.outbox.batch-size:50}") int batchSize) {
        this.repo = repo;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.published = Counter.builder("outbox.events.published").register(meters);
        this.failed = Counter.builder("outbox.events.failed").register(meters);
        meters.gauge("outbox.events.pending", repo, OutboxRepository::countByPublishedAtIsNull);
    }

    @Scheduled(fixedDelayString = "${cloudpulse.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repo.lockUnpublished(PageRequest.of(0, batchSize));
        for (OutboxEvent e : batch) {
            MDC.put(CorrelationIdFilter.MDC_KEY, e.getCorrelationId());
            try {
                publisher.publish(e.getTopic(), e.getPayload(), Map.of(
                        "eventId", e.getId().toString(),
                        "eventType", e.getEventType(),
                        "correlationId", e.getCorrelationId()));
                e.markPublished(Instant.now());
                published.increment();
                log.info("event published eventId={} type={} topic={}", e.getId(), e.getEventType(), e.getTopic());
            } catch (Exception ex) {
                e.markFailed(ex.toString());
                failed.increment();
                log.warn("event publish failed eventId={} attempts={}", e.getId(), e.getAttempts(), ex);
                break;   // preserve ordering; retry on next tick
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }
}
