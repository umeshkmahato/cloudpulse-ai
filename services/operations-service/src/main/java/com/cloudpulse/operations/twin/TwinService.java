package com.cloudpulse.operations.twin;

import com.cloudpulse.operations.detect.DetectionService;
import com.cloudpulse.operations.twin.TwinModel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TwinService {
    public record LiveNode(Component component, String liveStatus, Double riskScore) {}
    public record Graph(List<LiveNode> nodes, List<Dependency> edges, String note) {}

    private final JdbcTemplate jdbc;
    private final DetectionService detection;

    public TwinService(JdbcTemplate jdbc, DetectionService detection) {
        this.jdbc = jdbc;
        this.detection = detection;
    }

    public TwinModel load() {
        List<Component> comps = jdbc.query("select id, name, kind, description, deployment_status, criticality, parent_id, ui_x, ui_y from operations.twin_components order by id",
                (r, i) -> new Component(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getInt(8), r.getInt(9)));
        List<Dependency> deps = jdbc.query("select from_id, to_id, relation, mode, description from operations.twin_dependencies",
                (r, i) -> new Dependency(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)));
        Map<String, List<String>> txComps = new HashMap<>();
        jdbc.query("select transaction_id, component_id from operations.twin_transaction_components",
                r -> { txComps.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2)); });
        List<Transaction> txs = jdbc.query("select id, name, business_impact, revenue_impacting from operations.twin_transactions order by id",
                (r, i) -> new Transaction(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4), txComps.getOrDefault(r.getString(1), List.of())));
        Map<String, List<RecoveryStep>> rec = new HashMap<>();
        jdbc.query("select component_id, kind, step_no, step_text from operations.twin_recovery_steps order by component_id, kind, step_no",
                r -> { rec.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(new RecoveryStep(r.getString(2), r.getInt(3), r.getString(4))); });
        return new TwinModel(comps, deps, txs, rec);
    }

    /** Static model + LIVE overlay: service state comes from the detector, never from the twin tables. */
    public Graph graph() {
        TwinModel m = load();
        Map<String, DetectionService.CurrentState> live = detection.current().stream()
                .collect(Collectors.toMap(s -> s.assessment().service(), s -> s, (a, b) -> a));
        List<LiveNode> nodes = m.components().stream().map(c -> {
            if (!"ACTIVE_LOCAL".equals(c.deploymentStatus())) return new LiveNode(c, "NOT_DEPLOYED", null);
            var s = live.get(c.name());
            if (s == null) return new LiveNode(c, c.kind().equals("SERVICE") ? "UNKNOWN" : "N/A", null);
            return new LiveNode(c, s.assessment().status().name(), s.assessment().riskScore());
        }).toList();
        return new Graph(nodes, m.dependencies(),
                "Structure comes from the twin tables; liveStatus comes from the anomaly detector. PLANNED components are not deployed.");
    }

    public Optional<ImpactCalculator.Result> impact(String component, String scope) {
        return ImpactCalculator.calculate(load(), component, scope);
    }
}
