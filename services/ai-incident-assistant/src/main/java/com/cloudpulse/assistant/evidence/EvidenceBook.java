package com.cloudpulse.assistant.evidence;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-question collection of evidence with stable ids (EV-1, EV-2, ...). */
public class EvidenceBook {
    private final Map<String, Evidence> items = new LinkedHashMap<>();

    public Evidence add(String type, String source, String summary, JsonNode data, boolean synthetic) {
        String id = "EV-" + (items.size() + 1);
        Evidence e = new Evidence(id, type, source, Instant.now(), summary, data, synthetic);
        items.put(id, e);
        return e;
    }

    public boolean has(String id) { return items.containsKey(id); }

    public List<Evidence> all() { return new ArrayList<>(items.values()); }

    public boolean anySynthetic(List<String> ids) { return ids.stream().map(items::get).anyMatch(e -> e != null && e.synthetic()); }
}
