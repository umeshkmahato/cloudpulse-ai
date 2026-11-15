package com.cloudpulse.assistant;

import com.cloudpulse.assistant.Model.*;
import com.cloudpulse.assistant.engine.AnswerEngine;
import com.cloudpulse.assistant.engine.IntentClassifier;
import com.cloudpulse.assistant.evidence.Evidence;
import com.cloudpulse.assistant.evidence.EvidenceBook;
import com.cloudpulse.assistant.evidence.OperationsClient.EvidenceUnavailable;
import com.cloudpulse.assistant.narrate.GroundingValidator;
import com.cloudpulse.assistant.narrate.NarrativeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantServiceTest {
    final AnswerEngine engine = mock(AnswerEngine.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    AssistantService service(Optional<NarrativeProvider> np) {
        return new AssistantService(new IntentClassifier(), engine, np, new GroundingValidator(), mock(JdbcTemplate.class), mapper, new SimpleMeterRegistry());
    }

    void stubSlow(String text) {
        when(engine.slowService(any(), anyString())).thenAnswer(inv -> {
            EvidenceBook b = inv.getArgument(0);
            Evidence e = b.add("ASSESSMENT", "/x", "order-service ANOMALY risk 96", mapper.readTree("{\"risk\":96,\"latency\":2471}"), true);
            return new Answer("order-service is anomalous (risk 96/100).", List.of(new Claim(text, List.of(e.id()))), "HIGH", List.of("l"), "order-service");
        });
    }

    @Test
    void operationsApiDownProducesAnHonestNoEvidenceAnswer() {
        when(engine.slowService(any(), anyString())).thenThrow(new EvidenceUnavailable("/x", new RuntimeException()));
        AssistantAnswer a = service(Optional.empty()).ask("Why is the order service slow?", null);
        assertThat(a.confidence()).isEqualTo("NONE");
        assertThat(a.answer()).contains("cannot reach the Operations API").contains("will not guess");
        assertThat(a.claims()).isEmpty();
    }

    @Test
    void ruleBasedAnswerCitesEvidenceAndFlagsSyntheticData() {
        stubSlow("latency_ms is 2471 ms (baseline 298 ms).");
        AssistantAnswer a = service(Optional.empty()).ask("Why is the order service slow?", null);
        assertThat(a.answer()).contains("[EV-1]");
        assertThat(a.provider()).isEqualTo("rule-based");
        assertThat(a.containsSyntheticData()).isTrue();
        assertThat(a.limitations()).anyMatch(l -> l.contains("SYNTHETIC"));
        assertThat(a.evidence()).hasSize(1);
    }

    NarrativeProvider llm(String output) {
        return new NarrativeProvider() {
            public String name() { return "fake-llm"; }
            public Optional<String> rewrite(String q, Answer s, List<Evidence> ev) { return Optional.ofNullable(output); }
        };
    }

    @Test
    void groundedLlmOutputIsAccepted() {
        stubSlow("latency_ms is 2471 ms.");
        AssistantAnswer a = service(Optional.of(llm("Order service latency is 2471 ms and risk is 96 [EV-1]."))).ask("Why is the order service slow?", null);
        assertThat(a.answer()).startsWith("Order service latency is 2471 ms");
        assertThat(a.provider()).contains("fake-llm").contains("validated");
    }

    @Test
    void hallucinatedLlmOutputIsRejectedAndFallsBackToTheRuleBasedAnswer() {
        stubSlow("latency_ms is 2471 ms.");
        AssistantAnswer a = service(Optional.of(llm("Latency is 9999 ms; run kubectl scale now [EV-1]."))).ask("Why is the order service slow?", null);
        assertThat(a.answer()).contains("latency_ms is 2471 ms").doesNotContain("9999");
        assertThat(a.provider()).contains("rejected");
    }

    @Test
    void unavailableLlmFallsBackSilently() {
        stubSlow("latency_ms is 2471 ms.");
        AssistantAnswer a = service(Optional.of(llm(null))).ask("Why is the order service slow?", null);
        assertThat(a.provider()).contains("unavailable");
        assertThat(a.answer()).contains("[EV-1]");
    }

    @Test
    void aClaimWithoutEvidenceIsAProgrammingErrorAndNeverReachesTheUser() {
        EvidenceBook book = new EvidenceBook();
        assertThatThrownBy(() -> AssistantService.verifyClaims(new Answer("h", List.of(new Claim("x", List.of())), "HIGH", List.of(), null), book))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AssistantService.verifyClaims(new Answer("h", List.of(new Claim("x", List.of("EV-9"))), "HIGH", List.of(), null), book))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownQuestionsListWhatCanBeAnswered() {
        when(engine.unknown()).thenReturn(new Answer("I did not recognise that question. Try: ...", List.of(), "NONE", List.of(), null));
        AssistantAnswer a = service(Optional.empty()).ask("Tell me a joke", null);
        assertThat(a.intent()).isEqualTo("UNKNOWN");
        assertThat(a.confidence()).isEqualTo("NONE");
    }
}
