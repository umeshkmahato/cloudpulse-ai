package com.cloudpulse.operations.twin;

import java.util.List;
import java.util.Map;

/** In-memory snapshot of the relational twin tables. Pure data: impact analysis is a unit-testable function over it. */
public record TwinModel(List<Component> components, List<Dependency> dependencies, List<Transaction> transactions,
                        Map<String, List<RecoveryStep>> recovery) {

    public record Component(String id, String name, String kind, String description, String deploymentStatus,
                            String criticality, String parentId, int uiX, int uiY) {}

    /** "from" needs "to". mode: SYNC | ASYNC. relation: NEEDS | PROTECTS. */
    public record Dependency(String fromId, String toId, String relation, String mode, String description) {}

    public record Transaction(String id, String name, String businessImpact, boolean revenueImpacting, List<String> componentIds) {}

    public record RecoveryStep(String kind, int stepNo, String text) {}

    public Component component(String id) {
        return components.stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
    }
}
