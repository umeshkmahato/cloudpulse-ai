package com.cloudpulse.assistant;

import com.cloudpulse.assistant.Model.*;
import com.cloudpulse.assistant.engine.AnswerEngine;
import com.cloudpulse.assistant.engine.IntentClassifier;
import com.cloudpulse.assistant.engine.ServiceNames;
import com.cloudpulse.assistant.evidence.Evidence;
import com.cloudpulse.assistant.evidence.EvidenceBook;
import com.cloudpulse.assistant.evidence.OperationsClient.EvidenceUnavailable;
import com.cloudpulse.assistant.narrate.GroundingValidator;
import com.cloudpulse.assistant.narrate.NarrativeProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AssistantService {
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final IntentClassifier classifier;
    private final AnswerEngine engine;
    private final Optional<NarrativeProvider> narrative;
    private final GroundingValidator validator;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public AssistantService(IntentClassifier classifier, AnswerEngine engine, Optional<NarrativeProvider> narrative,
                            GroundingValidator validator, JdbcTemplate jdbc, ObjectMapper mapper, MeterRegistry meters) {
        this.classifier = classifier;
        this.engine = engine;
        this.narrative = narrative;
        this.validator = validator;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.meters = meters;
    }

    public AssistantAnswer ask(String question, String serviceHint) {
        Intent intent = classifier.classify(question);
        String service = serviceHint != null && !serviceHint.isBlank() ? serviceHint : ServiceNames.extract(question);
        EvidenceBook book = new EvidenceBook();
        Answer answer;
        try {
            answer = switch (intent) {
                case SLOW_SERVICE -> engine.slowService(book, service);
                case RESPONSIBLE_SERVICE -> engine.responsibleService(book);
                case ROOT_CAUSE -> engine.rootCause(book, service);
                case BUSINESS_IMPACT -> engine.businessImpact(book, service);
                case LAST_10_MINUTES -> engine.lastTenMinutes(book, Instant.now());
                case RECOMMENDED_ACTION -> engine.recommendedAction(book, service);
                case COST_IMPACT -> engine.costImpact(book, service);
                case FAILURE_WHAT_IF -> engine.failureWhatIf(book, service);
                case UNKNOWN -> engine.unknown();
            };
        } catch (EvidenceUnavailable e) {
            log.warn("evidence unavailable: {}", e.getMessage());
            answer = new Answer("I cannot reach the Operations API, so I cannot answer from evidence right now. I will not guess.", List.of(), "NONE",
                    List.of("Evidence source unavailable."), service);
        }
        verifyClaims(answer, book);

        String provider = "rule-based";
        String text = render(answer);
        if (narrative.isPresent() && !answer.claims().isEmpty()) {
            NarrativeProvider np = narrative.get();
            Optional<String> rewritten = np.rewrite(question, answer, book.all());
            if (rewritten.isPresent()) {
                List<String> problems = validator.violations(rewritten.get(), book.all(), question);
                if (problems.isEmpty()) { text = rewritten.get(); provider = np.name() + " (output validated against evidence)"; }
                else { meters.counter("assistant.llm.rejected").increment(); provider = "rule-based (" + np.name() + " output rejected: " + problems.get(0) + ")"; }
            } else {
                provider = "rule-based (" + np.name() + " unavailable)";
            }
        }

        List<Evidence> cited = book.all();
        boolean synthetic = answer.claims().stream().anyMatch(c -> book.anySynthetic(c.evidenceIds()));
        List<String> limitations = new ArrayList<>(answer.limitations());
        if (synthetic) limitations.add("Some evidence is SYNTHETIC test telemetry, not real GCP data.");
        AssistantAnswer out = new AssistantAnswer(UUID.randomUUID().toString(), question, intent.name(), answer.service(), text, answer.claims(),
                cited, answer.confidence(), limitations, provider, synthetic, Instant.now());
        meters.counter("assistant.questions", "intent", intent.name()).increment();
        audit(out);
        return out;
    }

    /** Invariant: no claim without evidence, and every evidence id must exist. Violations are programming errors, not user errors. */
    static void verifyClaims(Answer a, EvidenceBook book) {
        for (Claim c : a.claims()) {
            if (c.evidenceIds() == null || c.evidenceIds().isEmpty()) throw new IllegalStateException("claim without evidence: " + c.text());
            for (String id : c.evidenceIds()) if (!book.has(id)) throw new IllegalStateException("claim cites unknown evidence " + id);
        }
    }

    static String render(Answer a) {
        StringBuilder sb = new StringBuilder(a.headline());
        for (Claim c : a.claims()) sb.append("\n- ").append(c.text()).append(" ").append(c.evidenceIds().stream().map(i -> "[" + i + "]").collect(Collectors.joining()));
        return sb.toString();
    }

    private void audit(AssistantAnswer a) {
        try {
            jdbc.update("insert into assistant.answers (id, asked_at, question, intent, service, provider, confidence, answer, response_json) values (?,?,?,?,?,?,?,?,?)",
                    UUID.fromString(a.answerId()), Timestamp.from(a.generatedAt()), a.question(), a.intent(), a.service(), a.provider(), a.confidence(), a.answer(), mapper.writeValueAsString(a));
        } catch (org.springframework.dao.DataAccessException | JsonProcessingException e) {
            log.warn("audit write failed: {}", e.toString());
        }
    }

    public List<Map<String, Object>> history(int limit) {
        return jdbc.queryForList("select id, asked_at, question, intent, service, provider, confidence from assistant.answers order by asked_at desc limit ?", limit);
    }

}
