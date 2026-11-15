package com.cloudpulse.operations.detect;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Static downstream-impact map. Phase 10 replaces this with the queryable digital twin. */
@Component
public class DependencyModel {
    private static final Map<String, List<String>> AFFECTS = Map.of(
            "order-service", List.of("inventory-service (receives fewer/later orders)", "analytics-sink (event volume)"),
            "inventory-service", List.of("order-service (order confirmations delayed)", "analytics-sink (inventory events delayed)"),
            "operations-service", List.of("dashboard / incident detection (blind spot)"),
            "analytics-sink", List.of("dashboards (stale analytics)"));

    public List<String> affectedBy(String service) {
        return AFFECTS.getOrDefault(service, List.of());
    }
}
