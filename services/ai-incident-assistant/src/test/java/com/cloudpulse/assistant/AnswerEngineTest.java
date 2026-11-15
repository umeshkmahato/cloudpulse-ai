package com.cloudpulse.assistant;

import com.cloudpulse.assistant.Model.Answer;
import com.cloudpulse.assistant.Model.Claim;
import com.cloudpulse.assistant.engine.AnswerEngine;
import com.cloudpulse.assistant.evidence.EvidenceBook;
import com.cloudpulse.assistant.evidence.OperationsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerEngineTest {
    final ObjectMapper mapper = new ObjectMapper();
    final OperationsClient ops = mock(OperationsClient.class);
    AnswerEngine engine;
    EvidenceBook book;

    @BeforeEach
    void setUp() {
        engine = new AnswerEngine(ops, mapper);
        book = new EvidenceBook();
    }

    JsonNode json(String s) { try { return mapper.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }

    String finding(String metric, double cur, double base, boolean anomalous, double contrib) {
        return String.format("{\"metric\":\"%s\",\"current\":%s,\"baselineMean\":%s,\"baselineStd\":2,\"zScore\":20,\"relativeDeviation\":1.2,\"anomalous\":%s,\"riskContribution\":%s,\"explanation\":\"%s is %s (baseline %s): z=20.0.\"}",
                metric, cur, base, anomalous, contrib, metric, cur, base);
    }

    String state(String svc, String status, double risk, String findings) {
        return String.format("{\"assessment\":{\"service\":\"%s\",\"status\":\"%s\",\"riskScore\":%s,\"severity\":\"CRITICAL\",\"containsSyntheticData\":true,\"notes\":[\"n\"],\"findings\":[%s],\"forecasts\":[]},\"recommendations\":[],\"affectedServices\":[]}",
                svc, status, risk, findings);
    }

    void detection(String... states) { when(ops.get("/api/v1/detection/current")).thenReturn(json("[" + String.join(",", states) + "]")); }

    void assertEveryClaimHasEvidence(Answer a) {
        for (Claim c : a.claims()) {
            assertThat(c.evidenceIds()).isNotEmpty();
            c.evidenceIds().forEach(id -> assertThat(book.has(id)).isTrue());
        }
    }

    @Test
    void slowServiceReportsMeasuredAnomaliesAndLabelsCausesAsHypotheses() {
        detection(state("order-service", "ANOMALY", 96, String.join(",", finding("latency_ms", 2471, 298, true, 25), finding("cpu_pct", 88, 40, true, 20),
                finding("request_count", 978, 99, true, 5), finding("db_connections_pct", 90, 50, true, 13))));
        Answer a = engine.slowService(book, "order-service");
        assertThat(a.headline()).contains("96").contains("anomalous");
        assertThat(a.confidence()).isEqualTo("HIGH");
        assertThat(a.claims()).extracting(Claim::text).anyMatch(t -> t.contains("latency_ms is 2471"));
        assertThat(a.claims()).extracting(Claim::text).anyMatch(t -> t.startsWith("Hypothesis: load growth"))
                .anyMatch(t -> t.startsWith("Hypothesis: database connection saturation"));
        assertThat(a.limitations()).anyMatch(l -> l.contains("no traces or logs"));
        assertEveryClaimHasEvidence(a);
    }

    @Test
    void healthyServiceIsNotDescribedAsSlow() {
        detection(state("order-service", "OK", 0, finding("latency_ms", 305, 300, false, 0)));
        Answer a = engine.slowService(book, "order-service");
        assertThat(a.headline()).contains("does not currently look slow");
        assertThat(a.claims().get(0).text()).contains("latency_ms is 305");
        assertEveryClaimHasEvidence(a);
    }

    @Test
    void unknownServiceYieldsNoAnswerRatherThanAGuess() {
        detection(state("inventory-service", "OK", 0, finding("latency_ms", 300, 300, false, 0)));
        Answer a = engine.slowService(book, "payment-service");
        assertThat(a.confidence()).isEqualTo("NONE");
        assertThat(a.headline()).contains("no telemetry");
        assertEveryClaimHasEvidence(a);
    }

    String graph() {
        return """
            {"nodes":[{"component":{"id":"order-service","name":"order-service","deploymentStatus":"ACTIVE_LOCAL"}},
                      {"component":{"id":"inventory-service","name":"inventory-service","deploymentStatus":"ACTIVE_LOCAL"}},
                      {"component":{"id":"postgres","name":"postgres","deploymentStatus":"ACTIVE_LOCAL"}}],
             "edges":[{"fromId":"order-service","toId":"postgres","relation":"NEEDS","mode":"SYNC"},
                      {"fromId":"inventory-service","toId":"postgres","relation":"NEEDS","mode":"SYNC"}%s]}""";
    }

    @Test
    void independentAnomaliesAreRankedByRiskWithLowConfidence() {
        detection(state("order-service", "ANOMALY", 95, finding("cpu_pct", 90, 40, true, 20)), state("inventory-service", "ANOMALY", 66, finding("cpu_pct", 60, 40, true, 10)));
        when(ops.get("/api/v1/twin/graph")).thenReturn(json(graph().formatted("")));
        Answer a = engine.responsibleService(book);
        assertThat(a.service()).isEqualTo("order-service");
        assertThat(a.confidence()).isEqualTo("LOW");
        assertThat(a.limitations()).anyMatch(l -> l.contains("independently"));
        assertEveryClaimHasEvidence(a);
    }

    @Test
    void anAnomalyThatSynchronouslyDependsOnAnotherIsTreatedAsASymptom() {
        detection(state("order-service", "ANOMALY", 95, finding("latency_ms", 2000, 300, true, 25)), state("postgres", "ANOMALY", 70, finding("latency_ms", 900, 100, true, 25)));
        when(ops.get("/api/v1/twin/graph")).thenReturn(json(graph().formatted("")));
        Answer a = engine.responsibleService(book);
        assertThat(a.service()).isEqualTo("postgres");
        assertThat(a.confidence()).isEqualTo("MEDIUM");
        assertThat(a.claims()).extracting(Claim::text).anyMatch(t -> t.startsWith("order-service") && t.contains("may be a symptom"));
    }

    @Test
    void noAnomalyMeansNoResponsibleService() {
        detection(state("order-service", "OK", 0, finding("cpu_pct", 40, 40, false, 0)));
        Answer a = engine.responsibleService(book);
        assertThat(a.service()).isNull();
        assertThat(a.headline()).contains("No service is currently anomalous");
    }

    @Test
    void whatIfComesStraightFromTheTwinAndKeepsMessageRecoveryOptions() {
        when(ops.getIfExists(startsWith("/api/v1/twin/impact?component=inventory-service"))).thenReturn(Optional.of(json("""
            {"failed":{"id":"inventory-service","name":"inventory-service","kind":"SERVICE"},
             "hardImpacted":[],"delayedImpacted":[{"id":"order-service","name":"order-service","kind":"SERVICE"},{"id":"customer","name":"Customer / UI","kind":"CLIENT"}],
             "transactions":[{"id":"order-confirmation","name":"Order confirmation","status":"BLOCKED","businessImpact":"confirmations delayed","revenueImpacting":true}],
             "messageRecovery":[{"kind":"MESSAGE_RECOVERY","stepNo":1,"text":"orders-created messages queue for 7 days"}],
             "recovery":[{"kind":"RECOVERY","stepNo":1,"text":"restart the service"}],"mitigation":[],"businessImpact":"confirmations delayed","notes":[]}""")));
        detection(state("inventory-service", "OK", 0, finding("latency_ms", 300, 300, false, 0)));
        Answer a = engine.failureWhatIf(book, "inventory-service");
        assertThat(a.claims()).extracting(Claim::text).anyMatch(t -> t.contains("Customer / UI"))
                .anyMatch(t -> t.startsWith("Message recovery: orders-created messages queue"))
                .anyMatch(t -> t.startsWith("Recovery: restart"));
        assertThat(a.confidence()).isEqualTo("MEDIUM");
        assertThat(a.limitations()).anyMatch(l -> l.contains("not from a simulation"));
        assertEveryClaimHasEvidence(a);
    }

    @Test
    void whatIfForAnUnknownComponentSaysSo() {
        when(ops.getIfExists(startsWith("/api/v1/twin/impact?component=nope"))).thenReturn(Optional.empty());
        Answer a = engine.failureWhatIf(book, "nope");
        assertThat(a.confidence()).isEqualTo("NONE");
        assertThat(a.headline()).contains("no component named nope");
    }

    @Test
    void costAnswerRefusesToInventADollarFigure() {
        when(ops.get("/api/v1/incidents?status=OPEN&limit=50")).thenReturn(json("""
            [{"service":"order-service","synthetic":true,"recommendations":[{"actionId":"SCALE_DEPLOYMENT","title":"Scale out","rationale":"r","requiresApproval":true}]}]"""));
        Answer a = engine.costImpact(book, null);
        assertThat(a.confidence()).isEqualTo("NONE");
        assertThat(a.headline()).contains("cannot give a verified dollar estimate");
        assertThat(a.claims()).extracting(Claim::text).anyMatch(t -> t.contains("will not put a dollar figure"));
        assertThat(a.claims()).extracting(Claim::text).noneMatch(t -> t.matches(".*\\$\\s?\\d.*"));
        assertEveryClaimHasEvidence(a);
    }

    @Test
    void recommendationsAreListedAsAwaitingApprovalOrAbsent() {
        when(ops.get("/api/v1/incidents?status=OPEN&limit=50")).thenReturn(json("[]"));
        Answer none = engine.recommendedAction(book, null);
        assertThat(none.headline()).contains("no corrective action");

        EvidenceBook b2 = new EvidenceBook();
        when(ops.get("/api/v1/incidents?status=OPEN&limit=50")).thenReturn(json("""
            [{"service":"order-service","synthetic":true,"recommendations":[{"actionId":"SCALE_DEPLOYMENT","title":"Scale out order-service","rationale":"CPU saturation","requiresApproval":true}]}]"""));
        Answer a = engine.recommendedAction(b2, null);
        assertThat(a.claims().get(0).text()).contains("SCALE_DEPLOYMENT").contains("Requires approval: true");
        assertThat(a.limitations()).anyMatch(l -> l.contains("Nothing is executed"));
    }

    @Test
    void lastTenMinutesMentionsOngoingIncidentsThatAreStillBeingUpdated() {
        Instant now = Instant.now();
        when(ops.get("/api/v1/incidents?limit=50")).thenReturn(json(String.format("""
            [{"service":"order-service","kind":"DETECTED","severity":"CRITICAL","riskScore":95,"peakRisk":95,"status":"OPEN","openedAt":"%s","updatedAt":"%s","resolvedAt":null,"synthetic":true}]""",
                now.minusSeconds(3600), now.minusSeconds(5))));
        when(ops.get("/api/v1/analytics/overview?minutes=10")).thenReturn(json("{\"orders\":{\"orders\":0,\"order_value\":0},\"inventoryOutcomes\":[]}"));
        Answer a = engine.lastTenMinutes(book, now);
        assertThat(a.claims()).extracting(Claim::text).anyMatch(t -> t.contains("ongoing DETECTED incident on order-service still OPEN"));
    }

    @Test
    void lastTenMinutesOnlyIncludesRecentIncidentEvents() {
        Instant now = Instant.now();
        when(ops.get("/api/v1/incidents?limit=50")).thenReturn(json(String.format("""
            [{"service":"order-service","kind":"DETECTED","severity":"CRITICAL","riskScore":95,"peakRisk":95,"openedAt":"%s","resolvedAt":null,"synthetic":true},
             {"service":"inventory-service","kind":"DETECTED","severity":"HIGH","riskScore":66,"peakRisk":66,"openedAt":"%s","resolvedAt":"%s","synthetic":true}]""",
                now.minusSeconds(120), now.minusSeconds(3600), now.minusSeconds(3000))));
        when(ops.get("/api/v1/analytics/overview?minutes=10")).thenReturn(json("{\"orders\":{\"orders\":4,\"order_value\":80.5},\"inventoryOutcomes\":[{\"status\":\"RESERVED\",\"count\":3}]}"));
        Answer a = engine.lastTenMinutes(book, now);
        assertThat(a.headline()).startsWith("1 incident event");
        assertThat(a.claims()).extracting(Claim::text).anyMatch(t -> t.contains("order-service")).noneMatch(t -> t.contains("inventory-service"));
        assertEveryClaimHasEvidence(a);
    }
}
