package com.cloudpulse.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String topic;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @Column(name = "correlation_id", nullable = false)
    private String correlationId;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, String topic, String eventType, String correlationId, String payload, Instant createdAt) {
        this.id = id;
        this.topic = topic;
        this.eventType = eventType;
        this.correlationId = correlationId;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant at) { this.publishedAt = at; this.lastError = null; }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }

    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getEventType() { return eventType; }
    public String getCorrelationId() { return correlationId; }
    public String getPayload() { return payload; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
}
