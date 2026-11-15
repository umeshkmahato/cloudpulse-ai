import data from "../dr-results.json";
import { Empty, Panel, Pill } from "../components/ui";

interface Run { disaster: number; restore_s: number; rows_lost: number; data_loss_window_s: number }
interface Result {
  measured_at: string; injected: string; rto_met: boolean; rpo_met: boolean; write_outage_s?: number | null; write_failed_requests?: number; write_worst_latency_s?: number;
  read_outage_s?: number | null; orders_acknowledged?: number; acknowledged_orders_lost?: number; node_notready_detected_s?: number; capacity_restored_s?: number;
  postgres_ready_s?: number; pending_order_catchup_s?: number; outbox_events_waiting_at_peak?: number; worst_restore_s?: number; worst_data_loss_window_s?: number;
  base_backup_s?: number; archive_timeout_s?: number; runs?: Run[];
}
const results = (data as unknown as { results: Record<string, Result>; environment: string; targets: { rto_s: number; rpo_s: number } });
const R = results.results;
const LABEL: Record<string, string> = { "zone-failure": "Zone / node loss", "db-crash": "Database crash", "broker-outage": "Broker outage (60 s)", "pitr-restore": "Volume loss → restore from backup" };
const verdict = (r: Result) => (
  <span className="row"><Pill tone={r.rto_met ? "ok" : "bad"}>RTO {r.rto_met ? "met" : "missed"}</Pill><Pill tone={r.rpo_met ? "ok" : "bad"}>RPO {r.rpo_met ? "met" : "missed"}</Pill></span>
);

export default function DisasterRecovery() {
  const drills = Object.entries(R);
  return (
    <>
      <div className="banner info">
        <span>
          <strong>Experimental POC targets, not commitments:</strong> RTO {results.targets.rto_s / 60} min, RPO {results.targets.rpo_s / 60} min. The figures below were <strong>measured on a local kind cluster</strong> ({results.environment}).
          They validate the procedure and architecture, not cloud behaviour. Regional failover is design-only.
        </span>
      </div>
      <div className="grid cols-3">
        <Panel title="RTO target"><div className="value" style={{ fontSize: 28, fontWeight: 700 }}>5 min</div><div className="muted small">worst measured user-visible outage: {Math.max(0, ...drills.map(([, r]) => r.write_outage_s ?? 0))} s</div></Panel>
        <Panel title="RPO target"><div className="value" style={{ fontSize: 28, fontWeight: 700 }}>1 min</div><div className="muted small">acknowledged orders lost: {drills.reduce((a, [, r]) => a + (r.acknowledged_orders_lost ?? 0), 0)} · worst backup data-loss window: {R["pitr-restore"]?.worst_data_loss_window_s ?? "n/a"} s</div></Panel>
        <Panel title="Drills run"><div className="value" style={{ fontSize: 28, fontWeight: 700 }}>{drills.length}</div><div className="muted small">re-run: testing/dr/dr-drill.sh, pitr-drill.sh</div></Panel>
      </div>

      <Panel title="Measured drills">
        {drills.length === 0 ? <Empty>No drill results yet. Run <span className="mono">testing/dr/dr-drill.sh all</span> then <span className="mono">testing/dr/report.py</span>.</Empty> : (
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead><tr><th>Scenario</th><th>What was injected</th><th className="num">User-visible write outage</th><th className="num">Orders acked / lost</th><th>Verdict</th><th>Measured</th></tr></thead>
              <tbody>{drills.map(([k, r]) => (
                <tr key={k}>
                  <td>{LABEL[k] ?? k}</td><td className="small">{r.injected}</td>
                  <td className="num">{k === "pitr-restore" ? `restore ${r.worst_restore_s} s` : `${r.write_outage_s ?? "n/a"} s (worst latency ${r.write_worst_latency_s} s)`}</td>
                  <td className="num">{k === "pitr-restore" ? "n/a" : `${r.orders_acknowledged} / ${r.acknowledged_orders_lost}`}</td>
                  <td>{verdict(r)}</td><td className="small muted">{r.measured_at}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
      </Panel>

      {R["zone-failure"] && (
        <Panel title="Zone failure timeline (measured)">
          <ul>
            <li>Control plane noticed the dead node after <strong>{R["zone-failure"].node_notready_detected_s} s</strong> (kubelet heartbeat grace).</li>
            <li>Users saw failed or slow requests for <strong>{R["zone-failure"].write_outage_s} s</strong> (writes) and <strong>{R["zone-failure"].read_outage_s} s</strong> (reads served by a single-replica service) until routing converged.</li>
            <li>All desired replicas were Ready again on the surviving zone after <strong>{R["zone-failure"].capacity_restored_s} s</strong>.</li>
            <li>Acknowledged orders lost: <strong>{R["zone-failure"].acknowledged_orders_lost}</strong> of {R["zone-failure"].orders_acknowledged}.</li>
          </ul>
        </Panel>
      )}

      {R["pitr-restore"]?.runs && (
        <Panel title="Backup restore drill (WAL archive every 30 s)">
          <table>
            <thead><tr><th>Disaster instant</th><th className="num">Restore time</th><th className="num">Rows lost</th><th className="num">Data-loss window</th></tr></thead>
            <tbody>{R["pitr-restore"].runs.map((x) => <tr key={x.disaster}><td>#{x.disaster}</td><td className="num">{x.restore_s} s</td><td className="num">{x.rows_lost}</td><td className="num">{x.data_loss_window_s} s</td></tr>)}</tbody>
          </table>
          <div className="muted small" style={{ marginTop: 6 }}>The worst window sits close to the 60 s target: lower <span className="mono">archive_timeout</span> or add a streaming replica for a safer margin. The backup volume is in the same cluster, so this proves the procedure, not regional survival.</div>
        </Panel>
      )}

      <Panel title="Recovery building blocks">
        <table>
          <thead><tr><th>Capability</th><th>Mechanism</th><th>Status</th></tr></thead>
          <tbody>
            <tr><td>Pod / node / zone failure</td><td>Probes, 2 replicas on the order path, strict zone spread, 30 s eviction tolerance</td><td><Pill tone="ok">Measured locally</Pill></td></tr>
            <tr><td>Database crash and data loss</td><td>Restart on a persistent volume; base backup + WAL archive PITR</td><td><Pill tone="ok">Measured locally</Pill></td></tr>
            <tr><td>Broker outage</td><td>Transactional outbox, idempotent consumers, DLQ replay</td><td><Pill tone="ok">Measured locally</Pill></td></tr>
            <tr><td>Cloud SQL HA, backups, cross-region replica</td><td>Terraform (gated, billable)</td><td><Pill tone="muted">Authored, not applied</Pill></td></tr>
            <tr><td>Regional failover and traffic failover</td><td>Second region + global LB / DNS failover</td><td><Pill tone="muted">Design only</Pill></td></tr>
          </tbody>
        </table>
      </Panel>
    </>
  );
}
