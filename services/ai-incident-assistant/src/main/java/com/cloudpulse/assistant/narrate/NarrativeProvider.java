package com.cloudpulse.assistant.narrate;

import com.cloudpulse.assistant.Model.Answer;
import com.cloudpulse.assistant.evidence.Evidence;

import java.util.List;
import java.util.Optional;

/** Optional LLM rewrite of an already-grounded answer. It can only rephrase: facts and numbers must come from the evidence. */
public interface NarrativeProvider {
    String name();

    Optional<String> rewrite(String question, Answer structured, List<Evidence> evidence);
}
