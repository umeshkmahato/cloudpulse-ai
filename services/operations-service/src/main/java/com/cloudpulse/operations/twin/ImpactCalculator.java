package com.cloudpulse.operations.twin;

import com.cloudpulse.operations.twin.TwinModel.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * "What happens if X fails?" Walks NEEDS edges in reverse. A dependent is HARD-impacted only while every hop is synchronous;
 * crossing an asynchronous hop (a topic) makes the effect DELAYED (buffered, not lost). A delayed service delays only its
 * synchronous callers: it does not stop its own asynchronous producers. Topics are pass-through: delay reaches all subscribers.
 * A transaction is BLOCKED when a component it needs has failed or is hard-impacted, DELAYED when only a topic it flows through is backed up.
 * scope "current" ignores PLANNED (not deployed) components, "target" includes them.
 */
public final class ImpactCalculator {
    public enum Impact { FAILED, HARD, DELAYED }

    public record ComponentRef(String id, String name, String kind) {}
    public record TransactionImpact(String id, String name, String status, String businessImpact, boolean revenueImpacting) {}
    public record Result(ComponentRef failed, String scope, List<ComponentRef> hardImpacted, List<ComponentRef> delayedImpacted,
                         List<TransactionImpact> transactions, List<RecoveryStep> messageRecovery, List<RecoveryStep> recovery,
                         List<RecoveryStep> mitigation, String businessImpact, List<String> notes) {}

    public static Optional<Result> calculate(TwinModel m, String failedId, String scope) {
        TwinModel.Component failed = m.component(failedId);
        if (failed == null) return Optional.empty();
        boolean target = "target".equals(scope);
        List<String> notes = new ArrayList<>();
        if (!target && !"ACTIVE_LOCAL".equals(failed.deploymentStatus())) {
            notes.add(failed.name() + " is PLANNED (not deployed): nothing in the current environment depends on it. Use scope=target for the target architecture.");
        }
        Set<String> active = m.components().stream()
                .filter(c -> target || "ACTIVE_LOCAL".equals(c.deploymentStatus())).map(TwinModel.Component::id).collect(Collectors.toSet());
        List<Dependency> needs = m.dependencies().stream()
                .filter(d -> d.relation().equals("NEEDS") && active.contains(d.fromId()) && active.contains(d.toId())).toList();

        Map<String, Impact> impact = new LinkedHashMap<>();
        impact.put(failedId, Impact.FAILED);
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{failedId, true});
        while (!queue.isEmpty()) {
            Object[] cur = queue.poll();
            String id = (String) cur[0];
            boolean hard = (Boolean) cur[1];
            boolean isTopic = "TOPIC".equals(m.component(id).kind());
            for (Dependency d : needs) {
                if (!d.toId().equals(id)) continue;
                if (!hard && !isTopic && d.mode().equals("ASYNC")) continue;
                boolean nextHard = hard && d.mode().equals("SYNC");
                Impact prev = impact.get(d.fromId());
                if (prev == Impact.FAILED || prev == Impact.HARD || (prev == Impact.DELAYED && !nextHard)) continue;
                impact.put(d.fromId(), nextHard ? Impact.HARD : Impact.DELAYED);
                queue.add(new Object[]{d.fromId(), nextHard});
            }
        }

        List<ComponentRef> hardList = refs(m, impact, Impact.HARD);
        List<ComponentRef> delayedList = refs(m, impact, Impact.DELAYED);

        List<TransactionImpact> tx = new ArrayList<>();
        for (Transaction t : m.transactions()) {
            boolean blocked = t.componentIds().stream().anyMatch(c -> impact.get(c) == Impact.FAILED || impact.get(c) == Impact.HARD);
            // DELAYED = a topic this transaction flows through is backed up (events are buffered, not lost).
            boolean delayed = t.componentIds().stream().anyMatch(c -> impact.get(c) == Impact.DELAYED && "TOPIC".equals(m.component(c).kind()));
            if (blocked || delayed) tx.add(new TransactionImpact(t.id(), t.name(), blocked ? "BLOCKED" : "DELAYED", t.businessImpact(), t.revenueImpacting()));
        }
        tx.sort(Comparator.comparing((TransactionImpact t) -> !t.status().equals("BLOCKED")).thenComparing(t -> !t.revenueImpacting()));

        List<RecoveryStep> steps = m.recovery().getOrDefault(failedId, List.of());
        String business = tx.isEmpty() ? "No modelled business transaction depends on this component."
                : tx.get(0).businessImpact();
        return Optional.of(new Result(ref(failed), target ? "target" : "current", hardList, delayedList, tx,
                byKind(steps, "MESSAGE_RECOVERY"), byKind(steps, "RECOVERY"), byKind(steps, "MITIGATION"), business, notes));
    }

    private static List<ComponentRef> refs(TwinModel m, Map<String, Impact> impact, Impact wanted) {
        return impact.entrySet().stream().filter(e -> e.getValue() == wanted).map(e -> ref(m.component(e.getKey()))).toList();
    }

    private static ComponentRef ref(TwinModel.Component c) { return new ComponentRef(c.id(), c.name(), c.kind()); }

    private static List<RecoveryStep> byKind(List<RecoveryStep> steps, String kind) {
        return steps.stream().filter(s -> s.kind().equals(kind)).sorted(Comparator.comparingInt(RecoveryStep::stepNo)).toList();
    }

    private ImpactCalculator() {}
}
