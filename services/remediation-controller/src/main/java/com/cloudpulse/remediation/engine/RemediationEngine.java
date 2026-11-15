package com.cloudpulse.remediation.engine;

import com.cloudpulse.remediation.RemediationProperties;
import com.cloudpulse.remediation.events.RemediationEvents;
import com.cloudpulse.remediation.guard.AutoApprovalPolicy;
import com.cloudpulse.remediation.guard.ScalePlanner;
import com.cloudpulse.remediation.k8s.KubernetesScaler;
import com.cloudpulse.remediation.model.Remediation;
import com.cloudpulse.remediation.model.Status;
import com.cloudpulse.remediation.ops.OperationsClient;
import com.cloudpulse.remediation.store.RemediationStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The remediation lifecycle:
 *   incident recommends SCALE_DEPLOYMENT (executed as: raise the HPA minReplicas, restore it after recovery) -> PROPOSED (guardrails evaluated) -> APPROVED (human, or explicit policy) -> EXECUTING
 *   -> MONITORING -> RECOVERED | NOT_RECOVERED, with BLOCKED / REJECTED / EXPIRED / FAILED as other exits.
 * The ONLY executable action is "scale an allow-listed deployment within hard bounds". No shell, no arbitrary API calls.
 */
@Component
public class RemediationEngine {
    private static final Logger log = LoggerFactory.getLogger(RemediationEngine.class);
    public static final String ACTION = "SCALE_DEPLOYMENT";

    private final RemediationProperties props;
    private final RemediationStore store;
    private final OperationsClient ops;
    private final KubernetesScaler scaler;
    private final ScalePlanner planner;
    private final AutoApprovalPolicy policy;
    private final RemediationEvents events;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Clock clock;

    public RemediationEngine(RemediationProperties props, RemediationStore store, OperationsClient ops, KubernetesScaler scaler, ScalePlanner planner,
                             AutoApprovalPolicy policy, RemediationEvents events, ObjectMapper mapper, MeterRegistry meters, Clock clock) {
        this.props = props;
        this.store = store;
        this.ops = ops;
        this.scaler = scaler;
        this.planner = planner;
        this.policy = policy;
        this.events = events;
        this.mapper = mapper;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${cloudpulse.remediation.poll-interval-ms:10000}", initialDelayString = "${cloudpulse.remediation.poll-interval-ms:10000}")
    public void scheduled() {
        try {
            tick();
        } catch (Exception e) {
            meters.counter("remediation.loop.errors").increment();
            log.error("remediation loop failed", e);
        }
    }

    public synchronized void tick() {
        try { proposeFromIncidents(); } catch (Exception e) { log.warn("propose step failed: {}", e.toString()); }
        expireStale();
        executeApproved();
        monitorRecovery();
    }

    // ------------------------------------------------------------------ 1. propose
    /** Test hook: run only the proposer. */
    public void proposeFromIncidentsForTest() { proposeFromIncidents(); }

    void proposeFromIncidents() {
        JsonNode open = ops.openIncidents();
        if (open == null) return;
        for (JsonNode inc : open) {
            if (!hasAction(inc)) continue;
            UUID incidentId = UUID.fromString(inc.path("id").asText());
            if (store.existsForIncident(incidentId, ACTION)) continue;
            String service = inc.path("service").asText();
            Instant now = clock.instant();
            double risk = inc.path("riskScore").asDouble();
            String kind = inc.path("kind").asText();

            List<String> notes = new ArrayList<>();
            Status status = Status.PROPOSED;
            int from = 0, to = 0;
            String deployment = service;
            if (!planner.isAllowListed(service)) {
                status = Status.BLOCKED;
                notes.add("allow-list: DENIED - " + service + " may never be remediated by this controller");
            } else {
                try {
                    var st = scaler.state(props.namespace(), service);
                    notes.add("allow-list: ok");
                    var plan = planner.plan(service, st.desiredReplicas());
                    if (plan instanceof ScalePlanner.Allowed a) { from = a.from(); to = a.to(); notes.add(a.note()); }
                    else { status = Status.BLOCKED; notes.add("bounds: BLOCKED - " + ((ScalePlanner.Blocked) plan).reason()); }
                } catch (Exception e) {
                    status = Status.BLOCKED;
                    notes.add("cluster state unavailable: " + e.getMessage());
                }
            }
            boolean dbSaturated = dbSaturated(inc);
            if (dbSaturated) notes.add("warning: database connections are anomalous; scaling out multiplies connections and may worsen this");
            notes.add("mode: " + scaler.mode());

            Remediation r = new Remediation(UUID.randomUUID(), incidentId, service, ACTION, status, deployment, props.namespace(), from, to, risk, kind,
                    rationale(inc, from, to), toJson(notes), null, null, null, null, null, null, null, null, null, null,
                    inc.path("synthetic").asBoolean(), now, now, now.plus(Duration.ofMinutes(props.proposalTtlMinutes())), null, null);
            store.insert(r);
            if (status == Status.BLOCKED) store.transition(r.id(), Status.BLOCKED, Status.BLOCKED, Map.of("outcome", "BLOCKED", "outcome_detail", String.join("; ", notes)));
            meters.counter("remediation.proposals", "status", status.name()).increment();
            store.audit(r.id(), "controller", status == Status.BLOCKED ? "BLOCKED" : "PROPOSED", String.join("; ", notes));
            events.publish(r, "controller", String.join("; ", notes));
            log.info("remediation {} service={} status={} {}->{}", r.id(), service, status, from, to);
            if (status != Status.PROPOSED) continue;

            var decision = policy.evaluate(new AutoApprovalPolicy.Facts(service, kind, risk, dbSaturated));
            if (decision.approved()) {
                String name = policy.policyName(decision).orElse("policy");
                if (store.transition(r.id(), Status.PROPOSED, Status.APPROVED, Map.of("decided_by", "policy:" + name, "decided_at", now, "policy", name, "decision_comment", decision.reason()))) {
                    store.audit(r.id(), "policy:" + name, "AUTO_APPROVED", decision.reason());
                    events.publish(store.find(r.id()).orElseThrow(), "policy:" + name, decision.reason());
                }
            } else {
                store.audit(r.id(), "controller", "APPROVAL_REQUIRED", decision.reason());
            }
        }
    }

    // ------------------------------------------------------------------ 2. human decision
    public Remediation approve(UUID id, String operator, String comment) {
        Remediation r = store.find(id).orElseThrow(() -> new NoSuchElementException("remediation not found"));
        if (r.status() != Status.PROPOSED) throw new IllegalStateException("only PROPOSED remediations can be approved (this one is " + r.status() + ")");
        if (clock.instant().isAfter(r.expiresAt())) { expire(r); throw new IllegalStateException("proposal expired"); }
        if (!store.transition(id, Status.PROPOSED, Status.APPROVED, Map.of("decided_by", operator, "decided_at", clock.instant(), "decision_comment", comment == null ? "" : comment)))
            throw new IllegalStateException("remediation changed concurrently");
        store.audit(id, operator, "APPROVED", comment == null ? "" : comment);
        Remediation updated = store.find(id).orElseThrow();
        events.publish(updated, operator, "approved by operator");
        meters.counter("remediation.decisions", "decision", "approved").increment();
        return updated;
    }

    public Remediation reject(UUID id, String operator, String comment) {
        Remediation r = store.find(id).orElseThrow(() -> new NoSuchElementException("remediation not found"));
        if (r.status() != Status.PROPOSED) throw new IllegalStateException("only PROPOSED remediations can be rejected (this one is " + r.status() + ")");
        if (!store.transition(id, Status.PROPOSED, Status.REJECTED, Map.of("decided_by", operator, "decided_at", clock.instant(), "decision_comment", comment == null ? "" : comment, "outcome", "REJECTED")))
            throw new IllegalStateException("remediation changed concurrently");
        store.audit(id, operator, "REJECTED", comment == null ? "" : comment);
        Remediation updated = store.find(id).orElseThrow();
        events.publish(updated, operator, "rejected by operator");
        meters.counter("remediation.decisions", "decision", "rejected").increment();
        return updated;
    }

    void expireStale() {
        for (Remediation r : store.list("PROPOSED", 100)) if (clock.instant().isAfter(r.expiresAt())) expire(r);
    }

    private void expire(Remediation r) {
        if (store.transition(r.id(), Status.PROPOSED, Status.EXPIRED, Map.of("outcome", "EXPIRED", "outcome_detail", "no decision within " + props.proposalTtlMinutes() + " minutes"))) {
            store.audit(r.id(), "controller", "EXPIRED", "no decision within " + props.proposalTtlMinutes() + " minutes");
            events.publish(store.find(r.id()).orElseThrow(), "controller", "expired");
        }
    }

    // ------------------------------------------------------------------ 3. execute
    void executeApproved() {
        if (store.killSwitchOn()) { meters.counter("remediation.execution.paused").increment(); return; }   // approved work waits, nothing runs
        for (Remediation r : store.list("APPROVED", 50)) {
            if (!store.transition(r.id(), Status.APPROVED, Status.EXECUTING, Map.of())) continue;
            String actor = r.decidedBy() == null ? "controller" : r.decidedBy();
            try {
                var st = scaler.state(r.namespace(), r.deployment());
                Optional<Instant> last = store.lastExecutedAt(r.deployment());
                if (last.isPresent() && Duration.between(last.get(), clock.instant()).toMinutes() < props.cooldownMinutes()) { block(r, "cooldown: " + r.deployment() + " was scaled less than " + props.cooldownMinutes() + " minutes ago"); continue; }
                if (store.executedSince(clock.instant().minus(Duration.ofHours(1))) >= props.maxActionsPerHour()) { block(r, "rate limit: " + props.maxActionsPerHour() + " actions per hour reached"); continue; }

                var plan = planner.plan(r.service(), st.desiredReplicas());
                if (plan instanceof ScalePlanner.Blocked b) { block(r, b.reason()); continue; }
                var allowed = (ScalePlanner.Allowed) plan;
                int target = Math.min(r.toReplicas(), allowed.to());            // re-clamp against FRESH state: never exceed what is safe now
                if (target <= st.desiredReplicas()) { block(r, "already at " + st.desiredReplicas() + " replicas (target " + target + "): nothing to do"); continue; }
                store.audit(r.id(), actor, "EXECUTING", scaler.mode() + ": raise HPA capacity floor of " + r.deployment() + " " + st.floor() + " -> " + target + " (running " + st.desiredReplicas() + ")");
                var after = scaler.setCapacityFloor(r.namespace(), r.deployment(), target);
                if (after.floor() != target) throw new IllegalStateException("read-back mismatch: wanted floor " + target + " got " + after.floor());
                Instant now = clock.instant();
                store.transition(r.id(), Status.EXECUTING, Status.MONITORING, Map.of("executed_at", now, "observed_replicas", after.readyReplicas(), "from_replicas", st.desiredReplicas(), "to_replicas", target, "floor_before", st.floor()));
                store.audit(r.id(), actor, "EXECUTED", scaler.mode() + ": " + r.deployment() + " capacity floor now " + after.floor() + " (" + after.desiredReplicas() + " desired, " + after.readyReplicas() + " ready); monitoring recovery");
                events.publish(store.find(r.id()).orElseThrow(), actor, "scaled to " + target);
                meters.counter("remediation.executed", "mode", scaler.mode()).increment();
            } catch (Exception e) {
                store.transition(r.id(), Status.EXECUTING, Status.FAILED, Map.of("outcome", "FAILED", "outcome_detail", String.valueOf(e.getMessage())));
                store.audit(r.id(), "controller", "FAILED", String.valueOf(e.getMessage()));
                events.publish(store.find(r.id()).orElseThrow(), "controller", "failed: " + e.getMessage());
                meters.counter("remediation.failed").increment();
                log.error("execution failed id={}", r.id(), e);
            }
        }
    }

    private void block(Remediation r, String reason) {
        store.transition(r.id(), Status.EXECUTING, Status.BLOCKED, Map.of("outcome", "BLOCKED", "outcome_detail", reason));
        store.audit(r.id(), "controller", "BLOCKED", reason);
        events.publish(store.find(r.id()).orElseThrow(), "controller", reason);
        meters.counter("remediation.blocked").increment();
    }

    // ------------------------------------------------------------------ 4. monitor recovery
    void monitorRecovery() {
        for (Remediation r : store.list("MONITORING", 50)) {
            try {
                store.setObserved(r.id(), scaler.state(r.namespace(), r.deployment()).readyReplicas());
            } catch (Exception e) { log.debug("observe failed: {}", e.toString()); }
            Optional<JsonNode> inc = ops.incident(r.incidentId());
            Instant now = clock.instant();
            if (inc.isPresent() && "RESOLVED".equals(inc.get().path("status").asText())) {
                Instant resolved = inc.get().path("resolvedAt").isNull() ? now : Instant.parse(inc.get().path("resolvedAt").asText());
                long secs = Math.max(0, Duration.between(r.executedAt(), resolved).getSeconds());
                String detail = "incident resolved " + secs + "s after the action" + (r.synthetic() ? " (measured on SYNTHETIC telemetry; the scaling itself was " + scaler.mode() + ")" : "");
                if (store.transition(r.id(), Status.MONITORING, Status.RECOVERED, Map.of("recovered_at", resolved, "time_to_recover_s", secs, "outcome", "RECOVERED", "outcome_detail", detail))) {
                    store.audit(r.id(), "controller", "RECOVERED", detail);
                    restoreFloor(r);
                    events.publish(store.find(r.id()).orElseThrow(), "controller", detail);
                    meters.counter("remediation.outcome", "outcome", "recovered").increment();
                    meters.timer("remediation.time_to_recover").record(Duration.ofSeconds(secs));
                }
            } else if (now.isAfter(r.executedAt().plus(Duration.ofMinutes(props.monitorWindowMinutes())))) {
                String detail = "no recovery within " + props.monitorWindowMinutes() + " minutes of the action: escalate to an operator (scaling alone did not resolve the incident)";
                if (store.transition(r.id(), Status.MONITORING, Status.NOT_RECOVERED, Map.of("outcome", "NOT_RECOVERED", "outcome_detail", detail))) {
                    store.audit(r.id(), "controller", "NOT_RECOVERED", detail + ". Capacity floor left raised for the operator to decide.");
                    events.publish(store.find(r.id()).orElseThrow(), "controller", detail);
                    meters.counter("remediation.outcome", "outcome", "not_recovered").increment();
                }
            }
        }
    }

    /** After recovery the original HPA floor is restored so we never keep paying for the emergency capacity. */
    private void restoreFloor(Remediation r) {
        Integer before = store.find(r.id()).map(Remediation::floorBefore).orElse(null);
        if (before == null) return;
        try {
            scaler.setCapacityFloor(r.namespace(), r.deployment(), before);
            store.markFloorRestored(r.id());
            store.audit(r.id(), "controller", "CAPACITY_FLOOR_RESTORED", r.deployment() + " HPA floor back to " + before + "; the HPA scales down on its normal schedule");
        } catch (Exception e) {
            store.audit(r.id(), "controller", "RESTORE_FAILED", "could not restore the capacity floor to " + before + ": " + e.getMessage() + " (operator action needed)");
            log.error("floor restore failed id={}", r.id(), e);
        }
    }

    // ------------------------------------------------------------------ helpers
    private static boolean hasAction(JsonNode inc) {
        for (JsonNode rec : inc.path("recommendations")) if (ACTION.equals(rec.path("actionId").asText())) return true;
        return false;
    }

    private boolean dbSaturated(JsonNode inc) {
        for (JsonNode f : inc.path("evidence").path("findings")) {
            if ("db_connections_pct".equals(f.path("metric").asText()) && f.path("anomalous").asBoolean() && f.path("current").asDouble() >= props.dbSaturationBlockPct()) return true;
        }
        return false;
    }

    private String rationale(JsonNode inc, int from, int to) {
        return inc.path("summary").asText() + (to > from ? " Proposed: raise the capacity floor of " + inc.path("service").asText() + " so at least " + to + " replicas run (now " + from + ")." : "");
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
