package com.cloudpulse.assistant.engine;

import com.cloudpulse.assistant.Model.Answer;
import com.cloudpulse.assistant.Model.Claim;
import com.cloudpulse.assistant.evidence.Evidence;
import com.cloudpulse.assistant.evidence.EvidenceBook;
import com.cloudpulse.assistant.evidence.OperationsClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds answers ONLY from evidence fetched from the Operations API. Every claim carries evidence ids; when evidence is
 * missing the answer says so. Causes are labelled hypotheses (correlation is not causation). No writes, no commands.
 */
@Component
public class AnswerEngine {
    private static final String DEFAULT_SERVICE = "order-service";

    private final OperationsClient ops;
    private final ObjectMapper mapper;

    public AnswerEngine(OperationsClient ops, ObjectMapper mapper) {
        this.ops = ops;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- Why is X slow?
    public Answer slowService(EvidenceBook book, String requested) {
        String svc = requested != null ? requested : DEFAULT_SERVICE;
        JsonNode state = assessment(book, svc);
        List<String> lim = new ArrayList<>(List.of("Based on metrics only: no traces or logs are available to the assistant."));
        if (state == null) return noData(svc, book, lim);
        Evidence ev = evidenceFor(book, svc, state);
        String status = state.path("status").asText();
        if (status.equals("INSUFFICIENT_DATA") || status.equals("STALE")) {
            return new Answer("I cannot say whether " + svc + " is slow: detector status is " + status + ".",
                    List.of(new Claim(String.join(" ", textList(state.path("notes"))), List.of(ev.id()))), "NONE", lim, svc);
        }
        JsonNode lat = finding(state, "latency_ms");
        List<Claim> claims = new ArrayList<>();
        if (!state.path("status").asText().equals("ANOMALY") && !state.path("status").asText().equals("FORECAST")) {
            claims.add(new Claim(String.format("No evidence that %s is slow: latency_ms is %s ms (baseline %s ms), detector status %s, risk %s.",
                    svc, num(lat, "current"), num(lat, "baselineMean"), status, num(state, "riskScore")), List.of(ev.id())));
            return new Answer(svc + " does not currently look slow.", claims, "HIGH", lim, svc);
        }
        for (JsonNode f : anomalous(state)) claims.add(new Claim(f.path("explanation").asText(), List.of(ev.id())));
        addCauses(claims, state, ev, lim);
        return new Answer(String.format("%s is showing anomalous behaviour (risk %s/100, %s): %d metrics are unusual versus their baselines.",
                svc, num(state, "riskScore"), state.path("severity").asText(), anomalous(state).size()), claims, "HIGH", lim, svc);
    }

    // ---------------------------------------------------------------- Which service is responsible?
    public Answer responsibleService(EvidenceBook book) {
        JsonNode det = ops.get("/api/v1/detection/current");
        List<JsonNode> hot = new ArrayList<>();
        for (JsonNode s : det) if (isHot(s.path("assessment"))) hot.add(s.path("assessment"));
        List<String> lim = new ArrayList<>(List.of("Origin analysis uses only synchronous dependencies from the digital twin; asynchronous links are treated as decoupled."));
        if (hot.isEmpty()) {
            Evidence ev = book.add("DETECTION", "/api/v1/detection/current", "No service is currently anomalous", det, anySynthetic(det));
            return new Answer("No service is currently anomalous, so none is responsible.", List.of(new Claim("All evaluated services are OK, WATCH, STALE or lack data.", List.of(ev.id()))), "HIGH", lim, null);
        }
        JsonNode graph = ops.get("/api/v1/twin/graph");
        Evidence gev = book.add("TWIN_GRAPH", "/api/v1/twin/graph", "Dependency graph (structure + live status)", graph, false);
        Map<String, Set<String>> syncNeeds = syncNeeds(graph);
        Set<String> hotNames = hot.stream().map(h -> h.path("service").asText()).collect(Collectors.toSet());

        List<Claim> claims = new ArrayList<>();
        List<JsonNode> origins = new ArrayList<>();
        for (JsonNode h : hot) {
            String name = h.path("service").asText();
            Evidence ev = evidenceFor(book, name, h);
            Set<String> upstreamHot = closure(name, syncNeeds); upstreamHot.retainAll(hotNames); upstreamHot.remove(name);
            if (upstreamHot.isEmpty()) {
                origins.add(h);
                claims.add(new Claim(String.format("%s: risk %s, no anomalous synchronous dependency, so it is a candidate origin.", name, num(h, "riskScore")), List.of(ev.id(), gev.id())));
            } else {
                claims.add(new Claim(String.format("%s: risk %s, but it synchronously depends on anomalous %s, so it may be a symptom.", name, num(h, "riskScore"), upstreamHot), List.of(ev.id(), gev.id())));
            }
        }
        List<JsonNode> ranked = (origins.isEmpty() ? hot : origins).stream()
                .sorted(Comparator.comparingDouble((JsonNode n) -> n.path("riskScore").asDouble()).reversed()).toList();
        JsonNode top = ranked.get(0);
        String conf = origins.size() == 1 ? "MEDIUM" : "LOW";
        if (origins.size() > 1) lim.add("Several services are anomalous independently; the highest risk is reported first, but they may share a cause not visible in this data.");
        if (origins.isEmpty()) lim.add("Anomalous services depend on each other, so cause and effect cannot be separated from metrics alone.");
        return new Answer(String.format("Most likely responsible: %s (risk %s/100). This ranks anomaly severity and dependencies; it does not prove causation.",
                top.path("service").asText(), num(top, "riskScore")), claims, conf, lim, top.path("service").asText());
    }

    // ---------------------------------------------------------------- Likely root cause?
    public Answer rootCause(EvidenceBook book, String requested) {
        String svc = requested;
        if (svc == null) {
            Answer r = responsibleService(book);
            svc = r.service();
            if (svc == null) return new Answer("There is no active anomaly, so there is no root cause to analyse.", r.claims(), "HIGH", r.limitations(), null);
        }
        Answer slow = slowService(book, svc);
        List<Claim> claims = new ArrayList<>(slow.claims());
        List<String> lim = new ArrayList<>(slow.limitations());
        lim.add("Root cause here means the best-supported hypothesis from correlated metrics. Confirming it needs logs, traces or a change history.");
        String conf = claims.stream().anyMatch(c -> c.text().startsWith("Hypothesis")) ? "LOW" : slow.confidence();
        return new Answer("Likely root cause for " + svc + " (hypothesis, not proven): " + hypothesisHeadline(claims) , claims, conf, lim, svc);
    }

    // ---------------------------------------------------------------- Business impact?
    public Answer businessImpact(EvidenceBook book, String requested) {
        String svc = requested != null ? requested : topIncidentService(book);
        List<String> lim = new ArrayList<>(List.of("Impact comes from the twin's transaction model and the analytics store; no revenue-per-order or SLA data exists, so no money impact is computed beyond observed order value."));
        if (svc == null) {
            return new Answer("No active incident, so no current business impact is evidenced. Ask \"what happens if <service> fails?\" for a what-if.",
                    List.of(), "HIGH", lim, null);
        }
        Optional<JsonNode> impact = ops.getIfExists("/api/v1/twin/impact?component=" + svc + "&scope=current");
        if (impact.isEmpty()) return new Answer("The digital twin does not know component " + svc + ", so I cannot assess its impact.", List.of(), "NONE", lim, svc);
        Evidence iev = book.add("TWIN_IMPACT", "/api/v1/twin/impact?component=" + svc, "Impact analysis for " + svc, impact.get(), false);
        JsonNode overview = ops.get("/api/v1/analytics/overview?minutes=60");
        Evidence oev = book.add("ANALYTICS", "/api/v1/analytics/overview?minutes=60", "Orders and value in the last 60 minutes", overview, false);

        List<Claim> claims = new ArrayList<>();
        for (JsonNode t : impact.get().path("transactions")) {
            claims.add(new Claim(String.format("%s is %s: %s", t.path("name").asText(), t.path("status").asText(), t.path("businessImpact").asText()), List.of(iev.id())));
        }
        boolean revenue = false;
        for (JsonNode t : impact.get().path("transactions")) revenue |= t.path("revenueImpacting").asBoolean();
        if (revenue) {
            claims.add(new Claim(String.format("Over the last 60 minutes the platform accepted %s orders worth %s (analytics store). These flows depend on the affected transactions.",
                    overview.path("orders").path("orders").asText(), overview.path("orders").path("order_value").asText()), List.of(oev.id())));
        }
        return new Answer(claims.isEmpty() ? "No modelled business transaction depends on " + svc + "." : "Business impact if " + svc + " is degraded or down: " + impact.get().path("businessImpact").asText(),
                claims, "MEDIUM", lim, svc);
    }

    // ---------------------------------------------------------------- Last 10 minutes
    public Answer lastTenMinutes(EvidenceBook book, Instant now) {
        Instant from = now.minus(Duration.ofMinutes(10));
        JsonNode incidents = ops.get("/api/v1/incidents?limit=50");
        JsonNode overview = ops.get("/api/v1/analytics/overview?minutes=10");
        Evidence iev = book.add("INCIDENTS", "/api/v1/incidents?limit=50", "Incident history", incidents, StreamSupportHelper.anySynthetic(incidents));
        Evidence oev = book.add("ANALYTICS", "/api/v1/analytics/overview?minutes=10", "Business activity, last 10 minutes", overview, false);

        List<Entry> timeline = new ArrayList<>();
        for (JsonNode i : incidents) {
            addIf(timeline, i, "openedAt", from, i.path("kind").asText() + " incident opened on " + i.path("service").asText() + " (" + i.path("severity").asText() + ", risk " + num(i, "riskScore") + ")");
            if (!i.path("resolvedAt").isNull()) addIf(timeline, i, "resolvedAt", from, "incident on " + i.path("service").asText() + " resolved (peak risk " + num(i, "peakRisk") + ")");
            boolean openedInWindow = Instant.parse(i.path("openedAt").asText()).isAfter(from);
            if (i.path("status").asText().equals("OPEN") && !openedInWindow) {
                addIf(timeline, i, "updatedAt", from, "ongoing " + i.path("kind").asText() + " incident on " + i.path("service").asText() + " still OPEN (opened "
                        + i.path("openedAt").asText() + ", " + i.path("severity").asText() + ", risk " + num(i, "riskScore") + ")");
            }
        }
        List<Claim> claims = new ArrayList<>();
        timeline.sort(Comparator.comparing(Entry::at));
        for (Entry e : timeline) claims.add(new Claim(e.at() + ": " + e.text(), List.of(iev.id())));
        claims.add(new Claim(String.format("Business activity: %s orders worth %s in the last 10 minutes; inventory outcomes: %s.",
                overview.path("orders").path("orders").asText(), overview.path("orders").path("order_value").asText(), outcomes(overview)), List.of(oev.id())));
        return new Answer(timeline.isEmpty() ? "No incidents opened or resolved in the last 10 minutes." : timeline.size() + " incident event(s) in the last 10 minutes.",
                claims, "HIGH", List.of("Covers incidents and order/inventory activity only; individual metric samples are not narrated."), null);
    }

    // ---------------------------------------------------------------- Recommended action
    public Answer recommendedAction(EvidenceBook book, String requested) {
        JsonNode open = ops.get("/api/v1/incidents?status=OPEN&limit=50");
        Evidence ev = book.add("INCIDENTS", "/api/v1/incidents?status=OPEN", "Open incidents", open, StreamSupportHelper.anySynthetic(open));
        List<Claim> claims = new ArrayList<>();
        for (JsonNode i : open) {
            if (requested != null && !i.path("service").asText().equals(requested)) continue;
            for (JsonNode r : i.path("recommendations")) {
                claims.add(new Claim(String.format("%s: %s (%s). %s Requires approval: %s.", i.path("service").asText(), r.path("title").asText(),
                        r.path("actionId").asText(), r.path("rationale").asText(), r.path("requiresApproval").asText()), List.of(ev.id())));
            }
        }
        List<String> lim = List.of("Recommendations come from a predefined action catalogue. Nothing is executed by the assistant; an authorised operator must approve (Phase 12).");
        if (claims.isEmpty()) return new Answer("No open incident" + (requested != null ? " for " + requested : "") + ", so no corrective action is recommended.", List.of(new Claim("There are no open incidents matching the question.", List.of(ev.id()))), "HIGH", lim, requested);
        return new Answer(claims.size() + " recommended action(s). They are suggestions awaiting human approval.", claims, "MEDIUM", lim, requested);
    }

    // ---------------------------------------------------------------- Cost impact
    public Answer costImpact(EvidenceBook book, String requested) {
        JsonNode open = ops.get("/api/v1/incidents?status=OPEN&limit=50");
        Evidence iev = book.add("INCIDENTS", "/api/v1/incidents?status=OPEN", "Open incidents and recommended actions", open, StreamSupportHelper.anySynthetic(open));
        ObjectNode ref = mapper.createObjectNode();
        ref.put("currentEnvironment", "local Docker Compose");
        ref.put("currentCloudSpendUsd", 0);
        ref.put("pricingVerifiedForYourBillingAccount", false);
        ref.put("gkeAutopilotClusterFeePerHourUsd", 0.10);
        ref.put("gkeFeeSource", "web-checked list price in architecture/cost-estimate.md");
        Evidence cev = book.add("DOC", "architecture/cost-estimate.md", "Cost facts and their verification status", ref, false);
        boolean scale = false;
        for (JsonNode i : open) for (JsonNode r : i.path("recommendations")) scale |= r.path("actionId").asText().equals("SCALE_DEPLOYMENT");
        List<Claim> claims = new ArrayList<>();
        claims.add(new Claim("Nothing is deployed to the cloud: current cloud spend is 0 USD (local environment).", List.of(cev.id())));
        if (scale) {
            claims.add(new Claim("A scale-out action is recommended. Its cost = added replicas x pod CPU/memory requests x regional price x hours. Replica counts, requests and verified prices are not available to me, so I will not put a dollar figure on it.", List.of(iev.id(), cev.id())));
        } else {
            claims.add(new Claim("No scale-out action is currently recommended, so no incremental infrastructure cost is evidenced.", List.of(iev.id())));
        }
        claims.add(new Claim("For reference, a GKE Autopilot cluster fee was listed at 0.10 USD per cluster-hour in the cost document; this is unverified for your billing account and excludes pod compute.", List.of(cev.id())));
        return new Answer("I cannot give a verified dollar estimate of the cost impact.", claims, "NONE",
                List.of("Intentionally no invented numbers: needs the target cluster's replica counts/requests (Phase 11) and verified pricing for the chosen region."), requested);
    }

    // ---------------------------------------------------------------- What happens if X fails?
    public Answer failureWhatIf(EvidenceBook book, String component) {
        String id = component != null ? component : "inventory-service";
        Optional<JsonNode> res = ops.getIfExists("/api/v1/twin/impact?component=" + id + "&scope=current");
        if (res.isEmpty()) return new Answer("The digital twin has no component named " + id + ".", List.of(), "NONE", List.of("Ask about: order-service, inventory-service, operations-service, analytics-sink, postgres or a topic."), id);
        JsonNode r = res.get();
        Evidence ev = book.add("TWIN_IMPACT", "/api/v1/twin/impact?component=" + id, "Impact analysis if " + id + " fails", r, false);
        List<Claim> claims = new ArrayList<>();
        List<String> hard = names(r.path("hardImpacted")), delayed = names(r.path("delayedImpacted"));
        claims.add(new Claim("Hard-impacted (synchronous dependents): " + (hard.isEmpty() ? "none" : String.join(", ", hard)) + ".", List.of(ev.id())));
        claims.add(new Claim("Delayed (buffered by Pub/Sub, not lost): " + (delayed.isEmpty() ? "none" : String.join(", ", delayed)) + ".", List.of(ev.id())));
        List<String> clients = new ArrayList<>();
        for (JsonNode n : concat(r.path("hardImpacted"), r.path("delayedImpacted"))) if (List.of("CLIENT", "APPLICATION").contains(n.path("kind").asText())) clients.add(n.path("name").asText());
        if (!clients.isEmpty()) claims.add(new Claim("Dependent applications/clients: " + String.join(", ", clients) + ".", List.of(ev.id())));
        for (JsonNode t : r.path("transactions")) claims.add(new Claim("Transaction " + t.path("name").asText() + " would be " + t.path("status").asText() + ": " + t.path("businessImpact").asText(), List.of(ev.id())));
        for (JsonNode s : r.path("messageRecovery")) claims.add(new Claim("Message recovery: " + s.path("text").asText(), List.of(ev.id())));
        for (JsonNode s : r.path("recovery")) claims.add(new Claim("Recovery: " + s.path("text").asText(), List.of(ev.id())));
        for (JsonNode s : r.path("mitigation")) claims.add(new Claim("Mitigation: " + s.path("text").asText(), List.of(ev.id())));
        for (JsonNode n : r.path("notes")) claims.add(new Claim(n.asText(), List.of(ev.id())));
        JsonNode state = assessment(book, id);
        if (state != null) claims.add(new Claim(id + " currently has detector status " + state.path("status").asText() + " (risk " + num(state, "riskScore") + ").", List.of(evidenceFor(book, id, state).id())));
        return new Answer("If " + id + " fails: " + r.path("businessImpact").asText(), claims, "MEDIUM",
                List.of("What-if is computed from the static-structure digital twin and its transaction model, not from a simulation of load or timing."), id);
    }

    public Answer unknown() {
        String examples = Arrays.stream(com.cloudpulse.assistant.Model.Intent.values()).filter(i -> !i.example.isEmpty()).map(i -> "\"" + i.example + "\"").collect(Collectors.joining(", "));
        return new Answer("I can only answer from operational evidence, and I did not recognise that question. Try: " + examples + ".", List.of(), "NONE", List.of(), null);
    }

    // ---------------------------------------------------------------- helpers
    private JsonNode assessment(EvidenceBook book, String svc) {
        JsonNode det = ops.get("/api/v1/detection/current");
        for (JsonNode s : det) if (s.path("assessment").path("service").asText().equals(svc)) return s.path("assessment");
        return null;
    }

    private Evidence evidenceFor(EvidenceBook book, String svc, JsonNode a) {
        return book.add("ASSESSMENT", "/api/v1/detection/current#" + svc, svc + ": status " + a.path("status").asText() + ", risk " + num(a, "riskScore"), a, a.path("containsSyntheticData").asBoolean());
    }

    private Answer noData(String svc, EvidenceBook book, List<String> lim) {
        JsonNode det = ops.get("/api/v1/detection/current");
        Evidence ev = book.add("DETECTION", "/api/v1/detection/current", "Detector state for all services", det, anySynthetic(det));
        return new Answer("I have no telemetry for " + svc + ", so I cannot say whether it is slow.", List.of(new Claim("The detector has not evaluated any service named " + svc + ".", List.of(ev.id()))), "NONE", lim, svc);
    }

    private void addCauses(List<Claim> claims, JsonNode state, Evidence ev, List<String> lim) {
        Set<String> m = anomalous(state).stream().map(f -> f.path("metric").asText()).collect(Collectors.toSet());
        if (m.contains("cpu_pct") && m.contains("request_count")) claims.add(new Claim("Hypothesis: load growth. Request rate and CPU rose together, which is consistent with traffic exceeding capacity.", List.of(ev.id())));
        if (m.contains("db_connections_pct")) claims.add(new Claim("Hypothesis: database connection saturation. DB connection usage is far above baseline and can queue requests, raising latency.", List.of(ev.id())));
        if (m.contains("error_rate_pct") && !m.contains("cpu_pct")) claims.add(new Claim("Hypothesis: a failing dependency or a bad change. Errors rose without CPU pressure.", List.of(ev.id())));
        if (m.contains("event_latency_ms")) claims.add(new Claim("Event-processing latency is also elevated, so asynchronous consumers are lagging too.", List.of(ev.id())));
        if (m.contains("pod_restarts") || m.contains("memory_pct")) claims.add(new Claim("Memory/restart signals are anomalous: possible OOM kills or crash loops.", List.of(ev.id())));
        if (claims.stream().noneMatch(c -> c.text().startsWith("Hypothesis"))) lim.add("No standard cause pattern matched the anomalous metrics.");
    }

    private String hypothesisHeadline(List<Claim> claims) {
        return claims.stream().filter(c -> c.text().startsWith("Hypothesis")).findFirst().map(c -> c.text().substring("Hypothesis: ".length()))
                .orElse("no standard cause pattern matched; see the evidence.");
    }

    private String topIncidentService(EvidenceBook book) {
        JsonNode open = ops.get("/api/v1/incidents?status=OPEN&limit=50");
        JsonNode best = null;
        for (JsonNode i : open) if (best == null || i.path("riskScore").asDouble() > best.path("riskScore").asDouble()) best = i;
        if (best == null) return null;
        book.add("INCIDENTS", "/api/v1/incidents?status=OPEN", "Open incidents", open, StreamSupportHelper.anySynthetic(open));
        return best.path("service").asText();
    }

    private static boolean isHot(JsonNode a) { String s = a.path("status").asText(); return s.equals("ANOMALY") || s.equals("FORECAST"); }

    private static List<JsonNode> anomalous(JsonNode state) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode f : state.path("findings")) if (f.path("anomalous").asBoolean()) out.add(f);
        out.sort(Comparator.comparingDouble((JsonNode f) -> f.path("riskContribution").asDouble()).reversed());
        return out;
    }

    private static JsonNode finding(JsonNode state, String metric) {
        for (JsonNode f : state.path("findings")) if (f.path("metric").asText().equals(metric)) return f;
        return MissingNodeHolder.MISSING;
    }

    private static String num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return "n/a";
        return v.isNumber() ? stripZero(v.asDouble()) : v.asText();
    }

    private static String stripZero(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0); }

    private static Map<String, Set<String>> syncNeeds(JsonNode graph) {
        Set<String> active = new HashSet<>();
        Map<String, String> nameById = new HashMap<>();
        for (JsonNode n : graph.path("nodes")) {
            JsonNode c = n.path("component");
            nameById.put(c.path("id").asText(), c.path("name").asText());
            if (c.path("deploymentStatus").asText().equals("ACTIVE_LOCAL")) active.add(c.path("id").asText());
        }
        Map<String, Set<String>> needs = new HashMap<>();
        for (JsonNode e : graph.path("edges")) {
            if (!e.path("relation").asText().equals("NEEDS") || !e.path("mode").asText().equals("SYNC")) continue;
            String f = e.path("fromId").asText(), t = e.path("toId").asText();
            if (active.contains(f) && active.contains(t)) needs.computeIfAbsent(f, k -> new HashSet<>()).add(t);
        }
        return needs;
    }

    private static Set<String> closure(String start, Map<String, Set<String>> needs) {
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(start));
        while (!q.isEmpty()) for (String n : needs.getOrDefault(q.poll(), Set.of())) if (seen.add(n)) q.add(n);
        return seen;
    }

    private static boolean anySynthetic(JsonNode det) {
        for (JsonNode s : det) if (s.path("assessment").path("containsSyntheticData").asBoolean()) return true;
        return false;
    }

    private static List<String> textList(JsonNode arr) { List<String> out = new ArrayList<>(); arr.forEach(n -> out.add(n.asText())); return out; }

    private static List<String> names(JsonNode arr) { List<String> out = new ArrayList<>(); arr.forEach(n -> out.add(n.path("name").asText())); return out; }

    private static List<JsonNode> concat(JsonNode a, JsonNode b) { List<JsonNode> out = new ArrayList<>(); a.forEach(out::add); b.forEach(out::add); return out; }

    private static String outcomes(JsonNode overview) {
        List<String> parts = new ArrayList<>();
        for (JsonNode o : overview.path("inventoryOutcomes")) parts.add(o.path("count").asText() + " " + o.path("status").asText());
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    private record Entry(Instant at, String text) {}

    private static void addIf(List<Entry> timeline, JsonNode i, String field, Instant from, String text) {
        Instant at = Instant.parse(i.path(field).asText());
        if (at.isAfter(from)) timeline.add(new Entry(at, text));
    }

    /** Shared tiny holders (kept package-private to keep the engine file focused). */
    static final class MissingNodeHolder { static final JsonNode MISSING = com.fasterxml.jackson.databind.node.MissingNode.getInstance(); }

    static final class StreamSupportHelper {
        static boolean anySynthetic(JsonNode arr) { for (JsonNode n : arr) if (n.path("synthetic").asBoolean()) return true; return false; }
    }

}
