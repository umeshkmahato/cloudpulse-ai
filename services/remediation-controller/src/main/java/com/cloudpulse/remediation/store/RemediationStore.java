package com.cloudpulse.remediation.store;

import com.cloudpulse.remediation.model.Remediation;
import com.cloudpulse.remediation.model.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class RemediationStore {
    private static final Set<String> SETTABLE = Set.of("decided_by", "decision_comment", "decided_at", "policy", "executed_at", "observed_replicas",
            "recovered_at", "time_to_recover_s", "outcome", "outcome_detail", "from_replicas", "to_replicas", "guardrail_notes", "floor_before", "floor_restored_at");

    private final JdbcTemplate jdbc;

    public RemediationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant ts(ResultSet rs, String c) throws SQLException { Timestamp t = rs.getTimestamp(c); return t == null ? null : t.toInstant(); }

    private static final RowMapper<Remediation> MAP = (rs, i) -> new Remediation(
            UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("incident_id")), rs.getString("service"), rs.getString("action"),
            Status.valueOf(rs.getString("status")), rs.getString("deployment"), rs.getString("namespace"), rs.getInt("from_replicas"), rs.getInt("to_replicas"),
            rs.getDouble("risk_score"), rs.getString("incident_kind"), rs.getString("rationale"), rs.getString("guardrail_notes"), rs.getString("decided_by"),
            rs.getString("decision_comment"), ts(rs, "decided_at"), rs.getString("policy"), ts(rs, "executed_at"),
            (Integer) rs.getObject("observed_replicas"), ts(rs, "recovered_at"), (Long) rs.getObject("time_to_recover_s"), rs.getString("outcome"),
            rs.getString("outcome_detail"), rs.getBoolean("synthetic"), ts(rs, "created_at"), ts(rs, "updated_at"), ts(rs, "expires_at"),
            (Integer) rs.getObject("floor_before"), ts(rs, "floor_restored_at"));

    public void insert(Remediation r) {
        jdbc.update("""
                insert into remediation.remediations (id, incident_id, service, action, status, deployment, namespace, from_replicas, to_replicas,
                  risk_score, incident_kind, rationale, guardrail_notes, synthetic, created_at, updated_at, expires_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                r.id(), r.incidentId(), r.service(), r.action(), r.status().name(), r.deployment(), r.namespace(), r.fromReplicas(), r.toReplicas(),
                r.riskScore(), r.incidentKind(), r.rationale(), r.guardrailNotes(), r.synthetic(), Timestamp.from(r.createdAt()),
                Timestamp.from(r.updatedAt()), Timestamp.from(r.expiresAt()));
    }

    public Optional<Remediation> find(UUID id) {
        return jdbc.query("select * from remediation.remediations where id = ?", MAP, id).stream().findFirst();
    }

    public List<Remediation> list(String status, int limit) {
        return status == null
                ? jdbc.query("select * from remediation.remediations order by created_at desc limit ?", MAP, limit)
                : jdbc.query("select * from remediation.remediations where status = ? order by created_at desc limit ?", MAP, status, limit);
    }

    public boolean existsForIncident(UUID incidentId, String action) {
        Integer n = jdbc.queryForObject("select count(*) from remediation.remediations where incident_id = ? and action = ?", Integer.class, incidentId, action);
        return n != null && n > 0;
    }

    /** Compare-and-set: only moves the row if it is still in {@code from}. Prevents double approval / double execution. */
    public boolean transition(UUID id, Status from, Status to, Map<String, Object> sets) {
        StringBuilder sql = new StringBuilder("update remediation.remediations set status = ?, updated_at = ?");
        List<Object> args = new ArrayList<>(List.of(to.name(), Timestamp.from(Instant.now())));
        for (var e : sets.entrySet()) {
            if (!SETTABLE.contains(e.getKey())) throw new IllegalArgumentException("column not settable: " + e.getKey());
            sql.append(", ").append(e.getKey()).append(" = ?");
            args.add(e.getValue() instanceof Instant i ? Timestamp.from(i) : e.getValue());
        }
        sql.append(" where id = ? and status = ?");
        args.add(id);
        args.add(from.name());
        return jdbc.update(sql.toString(), args.toArray()) == 1;
    }

    public void markFloorRestored(UUID id) {
        jdbc.update("update remediation.remediations set floor_restored_at = ?, updated_at = ? where id = ?", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
    }

    public void setObserved(UUID id, int ready) {
        jdbc.update("update remediation.remediations set observed_replicas = ?, updated_at = ? where id = ?", ready, Timestamp.from(Instant.now()), id);
    }

    public Optional<Instant> lastExecutedAt(String deployment) {
        Timestamp t = jdbc.queryForObject("select max(executed_at) from remediation.remediations where deployment = ?", Timestamp.class, deployment);
        return Optional.ofNullable(t).map(Timestamp::toInstant);
    }

    public int executedSince(Instant since) {
        Integer n = jdbc.queryForObject("select count(*) from remediation.remediations where executed_at > ?", Integer.class, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    public void audit(UUID remediationId, String actor, String event, String details) {
        jdbc.update("insert into remediation.remediation_audit (remediation_id, at, actor, event, details) values (?,?,?,?,?)",
                remediationId, Timestamp.from(Instant.now()), actor, event, details);
    }

    public List<Map<String, Object>> auditFor(UUID remediationId) {
        return jdbc.queryForList("select id, at, actor, event, details from remediation.remediation_audit where remediation_id = ? order by id", remediationId);
    }

    public List<Map<String, Object>> recentAudit(int limit) {
        return jdbc.queryForList("select id, remediation_id, at, actor, event, details from remediation.remediation_audit order by id desc limit ?", limit);
    }

    public boolean killSwitchOn() {
        return "on".equals(jdbc.queryForObject("select value from remediation.controller_state where key = 'kill_switch'", String.class));
    }

    public void setKillSwitch(boolean on) {
        jdbc.update("update remediation.controller_state set value = ?, updated_at = ? where key = 'kill_switch'", on ? "on" : "off", Timestamp.from(Instant.now()));
    }
}
