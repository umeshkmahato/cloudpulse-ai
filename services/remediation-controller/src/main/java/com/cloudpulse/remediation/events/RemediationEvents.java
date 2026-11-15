package com.cloudpulse.remediation.events;

import com.cloudpulse.remediation.model.Remediation;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Publishes remediation.event on every state change (topic remediation-events). Best effort: the DB audit log is the source of truth. */
@Component
public class RemediationEvents {
    private static final Logger log = LoggerFactory.getLogger(RemediationEvents.class);

    public record Data(String remediationId, String incidentId, String service, String action, String deployment, int fromReplicas,
                       int toReplicas, String status, String actor, String detail, boolean synthetic) {}

    private final EventPublisher publisher;
    private final PubSubProperties props;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public RemediationEvents(EventPublisher publisher, PubSubProperties props, ObjectMapper mapper, MeterRegistry meters) {
        this.publisher = publisher;
        this.props = props;
        this.mapper = mapper;
        this.meters = meters;
    }

    public void publish(Remediation r, String actor, String detail) {
        try {
            String id = UUID.randomUUID().toString();
            String corr = "rem-" + r.id().toString().substring(0, 8);
            var data = new Data(r.id().toString(), r.incidentId().toString(), r.service(), r.action(), r.deployment(), r.fromReplicas(),
                    r.toReplicas(), r.status().name(), actor, detail, r.synthetic());
            var env = new EventEnvelope<>(id, "remediation.event", 1, Instant.now(), corr, EventEnvelope.SOURCE, data);
            publisher.publish(props.eventsTopic(), mapper.writeValueAsString(env), Map.of("eventId", id, "eventType", "remediation.event", "correlationId", corr));
            meters.counter("remediation.events.published", "status", r.status().name()).increment();
        } catch (Exception e) {
            meters.counter("remediation.events.failed").increment();
            log.warn("remediation event publish failed id={} reason={}", r.id(), e.toString());
        }
    }
}
