package com.cloudpulse.assistant;

import com.cloudpulse.assistant.evidence.Evidence;

import java.time.Instant;
import java.util.List;

public final class Model {
    /** A statement plus the evidence ids that support it. A claim without evidence is not allowed. */
    public record Claim(String text, List<String> evidenceIds) {}

    /** Engine output before rendering. confidence: HIGH (measured), MEDIUM (model based), LOW (hypothesis), NONE. */
    public record Answer(String headline, List<Claim> claims, String confidence, List<String> limitations, String service) {}

    public record AssistantAnswer(String answerId, String question, String intent, String service, String answer,
                                  List<Claim> claims, List<Evidence> evidence, String confidence, List<String> limitations,
                                  String provider, boolean containsSyntheticData, Instant generatedAt) {}

    public enum Intent {
        SLOW_SERVICE("Why is the order service slow?"),
        RESPONSIBLE_SERVICE("Which service is responsible?"),
        ROOT_CAUSE("What is the likely root cause?"),
        BUSINESS_IMPACT("What is the business impact?"),
        LAST_10_MINUTES("What happened in the last 10 minutes?"),
        RECOMMENDED_ACTION("What corrective action is recommended?"),
        COST_IMPACT("What is the estimated infrastructure cost impact?"),
        FAILURE_WHAT_IF("What happens if the inventory service fails?"),
        UNKNOWN("");

        public final String example;
        Intent(String example) { this.example = example; }
    }

    private Model() {}
}
