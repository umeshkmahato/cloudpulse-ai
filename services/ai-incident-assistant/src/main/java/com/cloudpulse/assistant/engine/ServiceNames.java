package com.cloudpulse.assistant.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps natural names in a question to twin component ids. */
public final class ServiceNames {
    private static final Map<Pattern, String> MAP = new LinkedHashMap<>();
    static {
        MAP.put(Pattern.compile("inventory[- ]?service|\\binventory\\b", Pattern.CASE_INSENSITIVE), "inventory-service");
        MAP.put(Pattern.compile("order[- ]?service|\\borders?\\b", Pattern.CASE_INSENSITIVE), "order-service");
        MAP.put(Pattern.compile("operations[- ]?service|\\boperations\\b", Pattern.CASE_INSENSITIVE), "operations-service");
        MAP.put(Pattern.compile("analytics[- ]?sink|\\banalytics\\b", Pattern.CASE_INSENSITIVE), "analytics-sink");
        MAP.put(Pattern.compile("postgres|database|\\bdb\\b", Pattern.CASE_INSENSITIVE), "postgres");
        MAP.put(Pattern.compile("orders-created", Pattern.CASE_INSENSITIVE), "orders-created");
        MAP.put(Pattern.compile("inventory-updated", Pattern.CASE_INSENSITIVE), "inventory-updated");
        MAP.put(Pattern.compile("application-metrics", Pattern.CASE_INSENSITIVE), "application-metrics");
    }

    /** First match wins; explicit hyphenated names are checked first so "inventory-updated" is not read as inventory-service. */
    public static String extract(String q) {
        for (String explicit : new String[]{"inventory-updated", "orders-created", "application-metrics"}) {
            if (q.toLowerCase().contains(explicit)) return explicit;
        }
        for (var e : MAP.entrySet()) if (e.getKey().matcher(q).find()) return e.getValue();
        return null;
    }

    private ServiceNames() {}
}
