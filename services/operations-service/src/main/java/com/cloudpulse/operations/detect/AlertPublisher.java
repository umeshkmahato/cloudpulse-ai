package com.cloudpulse.operations.detect;

import com.cloudpulse.operations.domain.Incident;
import com.cloudpulse.operations.events.EventEnvelope;
import com.cloudpulse.operations.events.EventPublisher;
import com.cloudpulse.operations.events.PubSubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Publishes alert.raised events (topic infrastructure-alerts). Best effort: detection must never fail because the broker is down. */
@Component
public class AlertPublisher {
    private static final Logger log = LoggerFactory.getLogger(AlertPublisher.class);

    /** alert.raised v1 data. state: OPENED | ESCALATED | DEESCALATED | RESOLVED. */
    public record AlertData(String incidentId, String service, String kind, String severity, double riskScore,
                            String state, String summary, List<String> affectedServices, boolean synthetic) {}

    private final EventPublisher publisher;
    private final PubSubProperties props;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public AlertPublisher(EventPublisher publisher, PubSubProperties props, ObjectMapper mapper, MeterRegistry meters) {
        this.publisher = publisher;
        this.props = props;
        this.mapper = mapper;
        this.meters = meters;
    }

    public void publish(Incident i, String state, List<String> affected) {
        try {
            String id = UUID.randomUUID().toString();
            String corr = "inc-" + i.getId().toString().substring(0, 8);
            var data = new AlertData(i.getId().toString(), i.getService(), i.getKind(), i.getSeverity(), i.getRiskScore(), state,
                    i.getSummary(), affected, i.isSynthetic());
            var env = new EventEnvelope<>(id, "alert.raised", 1, Instant.now(), corr, EventEnvelope.SOURCE, data);
            publisher.publish(props.alertsTopic(), mapper.writeValueAsString(env),
                    Map.of("eventId", id, "eventType", "alert.raised", "correlationId", corr));
            meters.counter("operations.alerts.published", "state", state).increment();
        } catch (Exception e) {
            meters.counter("operations.alerts.failed").increment();
            log.warn("alert publish failed incident={} state={} reason={}", i.getId(), state, e.toString());
        }
    }
}
