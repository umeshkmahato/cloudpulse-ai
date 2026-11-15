package com.cloudpulse.inventory.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Shared event envelope (see architecture/event-schemas.md). Consumers dedupe on eventId.
 * Shape: { eventId, eventType, eventVersion, occurredAt, correlationId, source, data }.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(String eventId, String eventType, int eventVersion, Instant occurredAt,
                               String correlationId, String source, T data) {
    public static final String SOURCE = "inventory-service";
}
