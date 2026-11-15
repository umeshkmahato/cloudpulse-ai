package com.cloudpulse.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "processed_events")
public class ProcessedEvent {
    @Id
    @Column(name = "event_id")
    private String eventId;
    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {}

    public ProcessedEvent(String eventId, Instant at) {
        this.eventId = eventId;
        this.processedAt = at;
    }

    public String getEventId() { return eventId; }
}
