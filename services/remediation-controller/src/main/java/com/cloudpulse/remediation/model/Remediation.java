package com.cloudpulse.remediation.model;

import java.time.Instant;
import java.util.UUID;

public record Remediation(UUID id, UUID incidentId, String service, String action, Status status, String deployment, String namespace,
                          int fromReplicas, int toReplicas, double riskScore, String incidentKind, String rationale, String guardrailNotes,
                          String decidedBy, String decisionComment, Instant decidedAt, String policy, Instant executedAt,
                          Integer observedReplicas, Instant recoveredAt, Long timeToRecoverSeconds, String outcome, String outcomeDetail,
                          boolean synthetic, Instant createdAt, Instant updatedAt, Instant expiresAt, Integer floorBefore, Instant floorRestoredAt) {}
