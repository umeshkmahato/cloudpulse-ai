package com.cloudpulse.remediation;

import com.cloudpulse.remediation.model.Remediation;
import com.cloudpulse.remediation.model.Status;
import com.cloudpulse.remediation.store.RemediationStore;

import java.time.Instant;
import java.util.*;

/** Test double: same behaviour contract as the JDBC store (including compare-and-set transitions), no database. */
class InMemoryStore extends RemediationStore {
    final Map<UUID, Remediation> rows = new LinkedHashMap<>();
    final List<String> audit = new ArrayList<>();
    boolean killSwitch;

    InMemoryStore() { super(null); }

    @Override public void insert(Remediation r) { rows.put(r.id(), r); }
    @Override public Optional<Remediation> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
    @Override public List<Remediation> list(String status, int limit) {
        return rows.values().stream().filter(r -> status == null || r.status().name().equals(status)).limit(limit).toList();
    }
    @Override public boolean existsForIncident(UUID incidentId, String action) {
        return rows.values().stream().anyMatch(r -> r.incidentId().equals(incidentId) && r.action().equals(action));
    }
    @Override public boolean transition(UUID id, Status from, Status to, Map<String, Object> sets) {
        Remediation r = rows.get(id);
        if (r == null || r.status() != from) return false;
        rows.put(id, new Remediation(r.id(), r.incidentId(), r.service(), r.action(), to, r.deployment(), r.namespace(),
                (Integer) sets.getOrDefault("from_replicas", r.fromReplicas()), (Integer) sets.getOrDefault("to_replicas", r.toReplicas()), r.riskScore(), r.incidentKind(),
                r.rationale(), r.guardrailNotes(), (String) sets.getOrDefault("decided_by", r.decidedBy()), (String) sets.getOrDefault("decision_comment", r.decisionComment()),
                (Instant) sets.getOrDefault("decided_at", r.decidedAt()), (String) sets.getOrDefault("policy", r.policy()), (Instant) sets.getOrDefault("executed_at", r.executedAt()),
                (Integer) sets.getOrDefault("observed_replicas", r.observedReplicas()), (Instant) sets.getOrDefault("recovered_at", r.recoveredAt()),
                (Long) sets.getOrDefault("time_to_recover_s", r.timeToRecoverSeconds()), (String) sets.getOrDefault("outcome", r.outcome()),
                (String) sets.getOrDefault("outcome_detail", r.outcomeDetail()), r.synthetic(), r.createdAt(), Instant.now(), r.expiresAt(),
                (Integer) sets.getOrDefault("floor_before", r.floorBefore()), (Instant) sets.getOrDefault("floor_restored_at", r.floorRestoredAt())));
        return true;
    }
    @Override public void markFloorRestored(UUID id) {
        Remediation r = rows.get(id);
        rows.put(id, new Remediation(r.id(), r.incidentId(), r.service(), r.action(), r.status(), r.deployment(), r.namespace(), r.fromReplicas(), r.toReplicas(), r.riskScore(), r.incidentKind(), r.rationale(), r.guardrailNotes(), r.decidedBy(), r.decisionComment(), r.decidedAt(), r.policy(), r.executedAt(), r.observedReplicas(), r.recoveredAt(), r.timeToRecoverSeconds(), r.outcome(), r.outcomeDetail(), r.synthetic(), r.createdAt(), Instant.now(), r.expiresAt(), r.floorBefore(), Instant.now()));
    }
    @Override public void setObserved(UUID id, int ready) { /* not needed */ }
    @Override public Optional<Instant> lastExecutedAt(String deployment) {
        return rows.values().stream().filter(r -> r.deployment().equals(deployment) && r.executedAt() != null).map(Remediation::executedAt).max(Comparator.naturalOrder());
    }
    @Override public int executedSince(Instant since) { return (int) rows.values().stream().filter(r -> r.executedAt() != null && r.executedAt().isAfter(since)).count(); }
    @Override public void audit(UUID rid, String actor, String event, String details) { audit.add(actor + "|" + event + "|" + details); }
    @Override public boolean killSwitchOn() { return killSwitch; }
    @Override public void setKillSwitch(boolean on) { killSwitch = on; }

    boolean auditHas(String event) { return audit.stream().anyMatch(a -> a.split("\\|")[1].equals(event)); }
}
