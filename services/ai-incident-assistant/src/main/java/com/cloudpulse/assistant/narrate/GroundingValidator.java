package com.cloudpulse.assistant.narrate;

import com.cloudpulse.assistant.evidence.Evidence;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guard for any model-written text (LLM output is untrusted). Accepts text only if:
 *  1. it cites at least one evidence id and every cited id exists,
 *  2. every number it states appears in the evidence (or the question), within rounding,
 *  3. it contains no code fences or shell/infra commands.
 * Anything else is rejected and the deterministic rule-based answer is used instead.
 */
@Component
public class GroundingValidator {
    private static final Pattern CITE = Pattern.compile("\\[(EV-\\d+)]");
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9_-])-?\\d+(?:[.,]\\d+)?");
    private static final Pattern COMMAND = Pattern.compile("```|\\b(kubectl|gcloud|terraform|sudo|helm|docker|curl|bash)\\b|\\brm -rf\\b|\\bsh -c\\b", Pattern.CASE_INSENSITIVE);

    public List<String> violations(String text, List<Evidence> evidence, String question) {
        List<String> v = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        evidence.forEach(e -> ids.add(e.id()));

        Matcher c = CITE.matcher(text);
        int cites = 0;
        while (c.find()) {
            cites++;
            if (!ids.contains(c.group(1))) v.add("cites unknown evidence " + c.group(1));
        }
        if (cites == 0) v.add("no evidence citations");

        if (COMMAND.matcher(text).find()) v.add("contains a code block or command");

        Set<Double> allowed = allowedNumbers(evidence, question);
        String withoutCites = CITE.matcher(text).replaceAll("");
        Matcher n = NUMBER.matcher(withoutCites);
        while (n.find()) {
            double d = Double.parseDouble(n.group().replace(',', '.'));
            if (!isAllowed(d, allowed)) v.add("number not in evidence: " + n.group());
        }
        return v;
    }

    public boolean isGrounded(String text, List<Evidence> evidence, String question) {
        return violations(text, evidence, question).isEmpty();
    }

    private Set<Double> allowedNumbers(List<Evidence> evidence, String question) {
        Set<Double> out = new HashSet<>(List.of(0.0, 1.0));
        StringBuilder src = new StringBuilder(question == null ? "" : question);
        for (Evidence e : evidence) src.append(' ').append(e.summary()).append(' ').append(e.data() == null ? "" : e.data().toString());
        Matcher m = NUMBER.matcher(src);
        while (m.find()) {
            try { out.add(Double.parseDouble(m.group().replace(',', '.'))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private boolean isAllowed(double d, Set<Double> allowed) {
        for (double a : allowed) {
            if (Math.abs(a - d) <= 1e-9) return true;
            if (Math.abs(a - d) / Math.max(Math.abs(a), 1.0) <= 0.005) return true;       // rounding
            if (Math.abs(Math.round(a) - d) <= 1e-9 || Math.abs(Math.round(a * 10) / 10.0 - d) <= 1e-9 || Math.abs(Math.round(a * 100) / 100.0 - d) <= 1e-9) return true;
        }
        return false;
    }
}
