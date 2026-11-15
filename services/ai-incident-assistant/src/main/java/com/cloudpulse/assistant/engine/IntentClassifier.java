package com.cloudpulse.assistant.engine;

import com.cloudpulse.assistant.Model.Intent;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** Deterministic keyword routing. Anything it cannot place is UNKNOWN: the assistant says so instead of guessing. */
@Component
public class IntentClassifier {
    private static final Pattern WHAT_IF = Pattern.compile("(what (happens|if)|if .*(fail|down|crash|outage)|fails?\\b|goes? down|is down)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST = Pattern.compile("\\b(cost|costs|spend|spending|billing|price|budget)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST = Pattern.compile("(last|past) (10|ten) min|what happened|recent(ly)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOT = Pattern.compile("root[ -]?cause|underlying cause|why did (this|it) happen", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESP = Pattern.compile("which service|responsible|culprit|to blame|origin", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUSINESS = Pattern.compile("business|revenue|customers? (impact|affected)|impact on", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION = Pattern.compile("recommend|corrective|remediat|what should|how (do|can) we fix|action", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLOW = Pattern.compile("slow|latency|sluggish|performance|degrad", Pattern.CASE_INSENSITIVE);

    public Intent classify(String q) {
        if (WHAT_IF.matcher(q).find() && ServiceNames.extract(q) != null) return Intent.FAILURE_WHAT_IF;
        if (COST.matcher(q).find()) return Intent.COST_IMPACT;
        if (LAST.matcher(q).find()) return Intent.LAST_10_MINUTES;
        if (ROOT.matcher(q).find()) return Intent.ROOT_CAUSE;
        if (RESP.matcher(q).find()) return Intent.RESPONSIBLE_SERVICE;
        if (BUSINESS.matcher(q).find()) return Intent.BUSINESS_IMPACT;
        if (ACTION.matcher(q).find()) return Intent.RECOMMENDED_ACTION;
        if (SLOW.matcher(q).find()) return Intent.SLOW_SERVICE;
        return Intent.UNKNOWN;
    }
}
