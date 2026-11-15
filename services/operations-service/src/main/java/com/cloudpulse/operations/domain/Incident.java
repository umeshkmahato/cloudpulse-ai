package com.cloudpulse.operations.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents")
public class Incident {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String service;
    @Column(nullable = false)
    private String kind;
    @Column(nullable = false)
    private String severity;
    @Column(name = "risk_score", nullable = false)
    private double riskScore;
    @Column(name = "peak_risk", nullable = false)
    private double peakRisk;
    @Column(nullable = false)
    private String status;
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "clean_streak", nullable = false)
    private int cleanStreak;
    @Column(nullable = false, length = 500)
    private String summary;
    @Column(nullable = false, columnDefinition = "text")
    private String affected;
    @Column(nullable = false, columnDefinition = "text")
    private String evidence;
    @Column(nullable = false, columnDefinition = "text")
    private String recommendations;
    @Column(nullable = false)
    private boolean synthetic;

    protected Incident() {}

    public Incident(UUID id, String service, String kind, String severity, double risk, Instant now, String summary,
                    String affected, String evidence, String recommendations, boolean synthetic) {
        this.id = id; this.service = service; this.kind = kind; this.severity = severity;
        this.riskScore = risk; this.peakRisk = risk; this.status = "OPEN";
        this.openedAt = now; this.updatedAt = now; this.summary = summary; this.affected = affected;
        this.evidence = evidence; this.recommendations = recommendations; this.synthetic = synthetic;
    }

    public void refresh(String kind, String severity, double risk, Instant now, String summary, String affected,
                        String evidence, String recommendations, boolean synthetic) {
        this.kind = kind; this.severity = severity; this.riskScore = risk; this.peakRisk = Math.max(peakRisk, risk);
        this.updatedAt = now; this.summary = summary; this.affected = affected; this.evidence = evidence;
        this.recommendations = recommendations; this.synthetic = synthetic; this.cleanStreak = 0;
    }

    public int bumpClean(Instant now) { this.updatedAt = now; return ++cleanStreak; }

    public void resolve(Instant now) { this.status = "RESOLVED"; this.resolvedAt = now; this.updatedAt = now; }

    public UUID getId() { return id; }
    public String getService() { return service; }
    public String getKind() { return kind; }
    public String getSeverity() { return severity; }
    public double getRiskScore() { return riskScore; }
    public double getPeakRisk() { return peakRisk; }
    public String getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getSummary() { return summary; }
    public String getAffected() { return affected; }
    public String getEvidence() { return evidence; }
    public String getRecommendations() { return recommendations; }
    public boolean isSynthetic() { return synthetic; }
}
