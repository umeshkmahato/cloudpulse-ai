package com.cloudpulse.operations.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "metric_samples")
public class MetricSample {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;
    @Column(nullable = false)
    private String service;
    @Column(nullable = false)
    private String metric;
    @Column(nullable = false)
    private double value;
    private String unit;
    @Column(nullable = false)
    private boolean synthetic;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected MetricSample() {}

    public MetricSample(String eventId, String service, String metric, double value, String unit,
                        boolean synthetic, Instant observedAt, Instant ingestedAt) {
        this.eventId = eventId;
        this.service = service;
        this.metric = metric;
        this.value = value;
        this.unit = unit;
        this.synthetic = synthetic;
        this.observedAt = observedAt;
        this.ingestedAt = ingestedAt;
    }

    public String getService() { return service; }
    public String getMetric() { return metric; }
    public double getValue() { return value; }
    public String getUnit() { return unit; }
    public boolean isSynthetic() { return synthetic; }
    public Instant getObservedAt() { return observedAt; }
}
