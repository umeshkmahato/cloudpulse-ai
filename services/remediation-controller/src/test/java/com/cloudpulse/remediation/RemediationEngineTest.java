package com.cloudpulse.remediation;

import com.cloudpulse.remediation.engine.RemediationEngine;
import com.cloudpulse.remediation.events.RemediationEvents;
import com.cloudpulse.remediation.guard.AutoApprovalPolicy;
import com.cloudpulse.remediation.guard.ScalePlanner;
import com.cloudpulse.remediation.k8s.KubernetesScaler;
import com.cloudpulse.remediation.model.Remediation;
import com.cloudpulse.remediation.model.Status;
import com.cloudpulse.remediation.ops.OperationsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RemediationEngineTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    static class FakeScaler implements KubernetesScaler {
        final Map<String, Integer> replicas = new HashMap<>(Map.of("order-service", 2, "inventory-service", 2));   // = HPA floor = running pods
        final List<String> calls = new ArrayList<>();
        boolean failScale;
        public State state(String ns, String d) { int r = replicas.getOrDefault(d, 2); return new State(r, r, r); }
        public State setCapacityFloor(String ns, String d, int n) throws Exception {
            if (failScale) throw new IllegalStateException("api down");
            calls.add(d + "=" + n); replicas.put(d, n); return new State(n, n, n);
        }
        public String mode() { return "fake"; }
    }

    final ObjectMapper mapper = new ObjectMapper();
    final InMemoryStore store = new InMemoryStore();
    final OperationsClient ops = mock(OperationsClient.class);
    final RemediationEvents events = mock(RemediationEvents.class);
    final FakeScaler scaler = new FakeScaler();
    final MutableClock clock = new MutableClock();
    RemediationEngine engine;

    JsonNode json(String s) { try { return mapper.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    String incident(String id, String service, String kind, double risk, boolean scaleRec, boolean dbSaturated) {
        return String.format("""
            {"id":"%s","service":"%s","kind":"%s","riskScore":%s,"status":"OPEN","synthetic":true,"summary":"Anomaly detected on %s.",
             "recommendations":[%s],"evidence":{"findings":[{"metric":"db_connections_pct","anomalous":%s,"current":92}]}}""",
                id, service, kind, risk, service, scaleRec ? "{\"actionId\":\"SCALE_DEPLOYMENT\",\"requiresApproval\":true}" : "{\"actionId\":\"INSPECT_ONLY\"}", dbSaturated);
    }

    void openIncidents(String... incidents) { when(ops.openIncidents()).thenReturn(json("[" + String.join(",", incidents) + "]")); }

    RemediationEngine build(boolean autoApprove) {
        var props = GuardrailTests.props(autoApprove, "tok");
        return new RemediationEngine(props, store, ops, scaler, new ScalePlanner(props), new AutoApprovalPolicy(props), events, mapper, new SimpleMeterRegistry(), clock);
    }

    static final String INC1 = "11111111-1111-1111-1111-111111111111";
    static final String INC2 = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void setUp() { engine = build(false); }

    Remediation only() { return store.rows.values().iterator().next(); }

    @Test
    void incidentWithScaleRecommendationBecomesAProposalNotAnAction() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        Remediation r = only();
        assertThat(r.status()).isEqualTo(Status.PROPOSED);
        assertThat(r.fromReplicas()).isEqualTo(2);
        assertThat(r.toReplicas()).isEqualTo(4);
        assertThat(r.guardrailNotes()).contains("allow-list: ok").contains("bounds ok");
        assertThat(scaler.calls).isEmpty();                              // nothing happens without a decision
        assertThat(store.auditHas("PROPOSED")).isTrue();
        assertThat(store.auditHas("APPROVAL_REQUIRED")).isTrue();
        verify(events).publish(any(), eq("controller"), anyString());
    }

    @Test
    void proposerIsIdempotentAcrossCycles() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        for (int i = 0; i < 5; i++) engine.tick();
        assertThat(store.rows).hasSize(1);
    }

    @Test
    void incidentsWithoutAScaleRecommendationAreIgnored() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, false, false));
        engine.tick();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void approvedActionExecutesExactlyOnceWithinBoundsAndIsMonitored() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        Remediation r = engine.approve(only().id(), "alice", "load spike confirmed");
        assertThat(r.status()).isEqualTo(Status.APPROVED);
        assertThat(r.decidedBy()).isEqualTo("alice");

        engine.tick();
        engine.tick();
        assertThat(scaler.calls).containsExactly("order-service=4");
        assertThat(only().status()).isEqualTo(Status.MONITORING);
        assertThat(only().executedAt()).isEqualTo(clock.now);
        assertThat(store.auditHas("EXECUTED")).isTrue();
    }

    @Test
    void servicesOutsideTheAllowListAreBlockedAndNeverTouched() {
        openIncidents(incident(INC1, "analytics-sink", "DETECTED", 99, true, false));
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.BLOCKED);
        assertThat(only().guardrailNotes()).contains("DENIED");
        assertThat(scaler.calls).isEmpty();
        assertThatThrownBy(() -> engine.approve(only().id(), "alice", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void alreadyAtMaximumIsBlockedWithAnExplanation() {
        scaler.replicas.put("order-service", 6);
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.BLOCKED);
        assertThat(only().guardrailNotes()).contains("maximum of 6");
    }

    @Test
    void rejectionNeverExecutes() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        engine.reject(only().id(), "bob", "known batch job");
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.REJECTED);
        assertThat(scaler.calls).isEmpty();
        assertThatThrownBy(() -> engine.approve(only().id(), "alice", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unattendedProposalsExpireAndCannotBeApprovedLate() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        clock.advance(Duration.ofMinutes(16));
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.EXPIRED);
        assertThatThrownBy(() -> engine.approve(only().id(), "alice", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void killSwitchPausesExecutionAndApprovedWorkResumesAfterwards() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        engine.approve(only().id(), "alice", "");
        store.killSwitch = true;
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.APPROVED);
        assertThat(scaler.calls).isEmpty();
        store.killSwitch = false;
        engine.tick();
        assertThat(scaler.calls).containsExactly("order-service=4");
    }

    @Test
    void cooldownBlocksASecondScaleOfTheSameDeployment() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false), incident(INC2, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        List<Remediation> rows = new ArrayList<>(store.rows.values());
        engine.approve(rows.get(0).id(), "alice", "");
        engine.tick();                       // first executes (2 -> 4)
        engine.approve(rows.get(1).id(), "alice", "");
        clock.advance(Duration.ofMinutes(2));
        engine.tick();                       // second hits the 10 minute cooldown
        assertThat(store.rows.get(rows.get(1).id()).status()).isEqualTo(Status.BLOCKED);
        assertThat(store.rows.get(rows.get(1).id()).outcomeDetail()).contains("cooldown");
        assertThat(scaler.calls).containsExactly("order-service=4");
    }

    @Test
    void hourlyRateLimitStopsARunawayLoop() {
        for (int i = 0; i < 3; i++) {   // three allowed actions on three deployments' history
            UUID id = UUID.randomUUID();
            store.insert(new Remediation(id, UUID.randomUUID(), "x" + i, "SCALE_DEPLOYMENT", Status.MONITORING, "x" + i, "cloudpulse", 2, 3, 90, "DETECTED", "r", "[]",
                    "a", "", clock.now, null, clock.now.minusSeconds(60), 3, null, null, null, null, true, clock.now, clock.now, clock.now.plusSeconds(600), null, null));
        }
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.proposeFromIncidentsForTest();
        Remediation r = store.rows.values().stream().filter(x -> x.service().equals("order-service")).findFirst().orElseThrow();
        engine.approve(r.id(), "alice", "");
        engine.tick();
        assertThat(store.rows.get(r.id()).status()).isEqualTo(Status.BLOCKED);
        assertThat(store.rows.get(r.id()).outcomeDetail()).contains("rate limit");
    }

    @Test
    void executionFailureIsRecordedAsFailedNotSilentlyRetried() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        engine.approve(only().id(), "alice", "");
        scaler.failScale = true;
        engine.tick();
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.FAILED);
        assertThat(only().outcomeDetail()).contains("api down");
    }

    @Test
    void measuresRecoveryTimeWhenTheIncidentResolves() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        engine.approve(only().id(), "alice", "");
        engine.tick();
        Instant executed = only().executedAt();
        when(ops.incident(UUID.fromString(INC1))).thenReturn(Optional.of(json("{\"status\":\"RESOLVED\",\"resolvedAt\":\"" + executed.plusSeconds(75) + "\"}")));
        clock.advance(Duration.ofSeconds(80));
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.RECOVERED);
        assertThat(only().timeToRecoverSeconds()).isEqualTo(75);
        assertThat(only().outcomeDetail()).contains("SYNTHETIC");
        assertThat(scaler.calls).containsExactly("order-service=4", "order-service=2");   // emergency floor raised, then restored
        assertThat(only().floorBefore()).isEqualTo(2);
        assertThat(only().floorRestoredAt()).isNotNull();
        assertThat(store.auditHas("CAPACITY_FLOOR_RESTORED")).isTrue();
    }

    @Test
    void reportsNotRecoveredWhenTheWindowPassesWithoutResolution() {
        openIncidents(incident(INC1, "order-service", "DETECTED", 95, true, false));
        engine.tick();
        engine.approve(only().id(), "alice", "");
        engine.tick();
        when(ops.incident(UUID.fromString(INC1))).thenReturn(Optional.of(json("{\"status\":\"OPEN\"}")));
        clock.advance(Duration.ofMinutes(6));
        engine.tick();
        assertThat(only().status()).isEqualTo(Status.NOT_RECOVERED);
        assertThat(only().outcomeDetail()).contains("escalate");
        assertThat(scaler.calls).containsExactly("order-service=4");                      // floor stays raised: an operator decides
    }

    @Test
    void explicitPolicyCanApproveButNeverWhenScalingCouldWorsenDbSaturation() {
        RemediationEngine auto = build(true);
        openIncidents(incident(INC1, "order-service", "DETECTED", 92, true, false), incident(INC2, "order-service", "DETECTED", 92, true, true));
        auto.tick();
        Map<String, Remediation> byIncident = new HashMap<>();
        store.rows.values().forEach(r -> byIncident.put(r.incidentId().toString(), r));
        assertThat(byIncident.get(INC1).status()).isEqualTo(Status.MONITORING);          // approved by policy and executed in the same cycle
        assertThat(byIncident.get(INC1).decidedBy()).isEqualTo("policy:scale-out-v1");
        assertThat(byIncident.get(INC2).status()).isEqualTo(Status.PROPOSED);            // DB saturated: human required
        assertThat(store.audit).anyMatch(a -> a.startsWith("policy:scale-out-v1|AUTO_APPROVED"));
    }
}
