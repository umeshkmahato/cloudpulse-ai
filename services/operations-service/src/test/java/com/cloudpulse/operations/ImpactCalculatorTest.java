package com.cloudpulse.operations;

import com.cloudpulse.operations.twin.ImpactCalculator;
import com.cloudpulse.operations.twin.ImpactCalculator.Result;
import com.cloudpulse.operations.twin.TwinModel;
import com.cloudpulse.operations.twin.TwinModel.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImpactCalculatorTest {
    static Component c(String id, String kind, String status) { return new Component(id, id, kind, "d", status, "high", null, 0, 0); }
    static Dependency n(String from, String to, String mode) { return new Dependency(from, to, "NEEDS", mode, ""); }

    // Same shape as the seeded architecture (subset), including PLANNED components.
    final TwinModel model = new TwinModel(
            List.of(c("customer", "CLIENT", "ACTIVE_LOCAL"), c("order-service", "SERVICE", "ACTIVE_LOCAL"), c("inventory-service", "SERVICE", "ACTIVE_LOCAL"),
                    c("analytics-sink", "SERVICE", "ACTIVE_LOCAL"), c("orders-created", "TOPIC", "ACTIVE_LOCAL"), c("inventory-updated", "TOPIC", "ACTIVE_LOCAL"),
                    c("postgres", "DATABASE", "ACTIVE_LOCAL"), c("analytics-store", "DATABASE", "ACTIVE_LOCAL"),
                    c("deploy-order", "DEPLOYMENT", "PLANNED"), c("gke", "CLOUD_RESOURCE", "PLANNED")),
            List.of(n("customer", "order-service", "SYNC"), n("order-service", "postgres", "SYNC"), n("order-service", "inventory-updated", "ASYNC"),
                    n("orders-created", "order-service", "ASYNC"), n("inventory-service", "orders-created", "ASYNC"), n("inventory-service", "postgres", "SYNC"),
                    n("inventory-updated", "inventory-service", "ASYNC"), n("analytics-sink", "orders-created", "ASYNC"), n("analytics-sink", "inventory-updated", "ASYNC"),
                    n("analytics-sink", "analytics-store", "SYNC"), n("analytics-store", "postgres", "SYNC"),
                    n("order-service", "deploy-order", "SYNC"), n("deploy-order", "gke", "SYNC"),
                    new Dependency("netpol", "order-service", "PROTECTS", "SYNC", "")),
            List.of(new Transaction("order-intake", "Order intake", "no new orders", true, List.of("order-service", "postgres")),
                    new Transaction("stock-reservation", "Stock reservation", "fulfilment delayed", true, List.of("inventory-service", "orders-created", "postgres")),
                    new Transaction("order-confirmation", "Order confirmation", "confirmations delayed", true, List.of("order-service", "inventory-updated", "inventory-service")),
                    new Transaction("analytics-ingest", "Analytics ingestion", "dashboards stale", false, List.of("analytics-sink", "analytics-store", "orders-created", "inventory-updated"))),
            Map.of("inventory-service", List.of(new RecoveryStep("MESSAGE_RECOVERY", 1, "queue drains"), new RecoveryStep("RECOVERY", 1, "restart"))));

    static List<String> ids(List<ImpactCalculator.ComponentRef> l) { return l.stream().map(ImpactCalculator.ComponentRef::id).toList(); }

    @Test
    void inventoryFailureDelaysConfirmationsButDoesNotBlockOrderIntake() {
        Result r = ImpactCalculator.calculate(model, "inventory-service", "current").orElseThrow();
        assertThat(ids(r.hardImpacted())).isEmpty();
        assertThat(ids(r.delayedImpacted())).contains("inventory-updated", "order-service", "customer", "analytics-sink").doesNotContain("orders-created");
        assertThat(r.transactions()).extracting("id", "status").contains(
                org.assertj.core.groups.Tuple.tuple("stock-reservation", "BLOCKED"),
                org.assertj.core.groups.Tuple.tuple("order-confirmation", "BLOCKED"),
                org.assertj.core.groups.Tuple.tuple("analytics-ingest", "DELAYED"));
        assertThat(r.transactions()).extracting("id").doesNotContain("order-intake");
        assertThat(r.messageRecovery()).hasSize(1);
        assertThat(r.recovery()).hasSize(1);
    }

    @Test
    void databaseFailureHardBlocksEverySynchronousDependent() {
        Result r = ImpactCalculator.calculate(model, "postgres", "current").orElseThrow();
        assertThat(ids(r.hardImpacted())).contains("order-service", "customer", "inventory-service", "analytics-store", "analytics-sink");
        assertThat(r.transactions().get(0).status()).isEqualTo("BLOCKED");
        assertThat(r.transactions()).extracting("id").contains("order-intake", "stock-reservation", "analytics-ingest");
    }

    @Test
    void plannedComponentsAreIgnoredInCurrentScopeAndExplainedNotInvented() {
        Result current = ImpactCalculator.calculate(model, "gke", "current").orElseThrow();
        assertThat(ids(current.hardImpacted())).isEmpty();
        assertThat(current.notes()).anyMatch(s -> s.contains("PLANNED"));

        Result target = ImpactCalculator.calculate(model, "gke", "target").orElseThrow();
        assertThat(ids(target.hardImpacted())).contains("deploy-order", "order-service", "customer");
    }

    @Test
    void protectsRelationsDoNotPropagateFailure() {
        assertThat(ImpactCalculator.calculate(model, "order-service", "current").orElseThrow().hardImpacted()).extracting("id").doesNotContain("netpol");
    }

    @Test
    void unknownComponentIsEmptyNotGuessed() {
        assertThat(ImpactCalculator.calculate(model, "nope", "current")).isEmpty();
    }
}
