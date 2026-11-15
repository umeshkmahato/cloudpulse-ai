import { useState } from "react";
import { api } from "../api";
import { Empty, ErrorBanner, MetricCard, Panel, Pill, SeverityBadge, StatusPill, SyntheticBadge } from "../components/ui";
import { ago, fmtNum } from "../format";
import { usePolling } from "../hooks";
import { useRefresh } from "../refresh";
import { RemediationPanel } from "../components/RemediationPanel";
import type { Assessment, Incident } from "../types";

const sev = (s: string) => s.toLowerCase() as "critical" | "high" | "medium" | "low";
const STATUS_TONE: Record<Assessment["status"], "ok" | "warn" | "bad" | "info" | "muted"> = {
  OK: "ok", WATCH: "info", ANOMALY: "bad", FORECAST: "warn", INSUFFICIENT_DATA: "muted", STALE: "muted",
};

export function KindBadge({ kind }: { kind: Incident["kind"] }) {
  return kind === "DETECTED"
    ? <Pill tone="bad" title="Metrics are statistically unusual right now">DETECTED ANOMALY</Pill>
    : <Pill tone="warn" title="Trend extrapolation, low confidence. Not an outage.">FORECAST · LOW CONFIDENCE</Pill>;
}

export default function Predictive() {
  const { intervalMs } = useRefresh();
  const detection = usePolling(() => api.detection(), intervalMs);
  const open = usePolling(() => api.incidents("OPEN"), intervalMs);
  const history = usePolling(() => api.incidents("RESOLVED"), intervalMs);
  const [selected, setSelected] = useState<string | null>(null);

  const states = detection.data ?? [];
  const openIncidents = open.data ?? [];
  const detail = [...openIncidents, ...(history.data ?? [])].find((i) => i.id === selected) ?? openIncidents[0] ?? null;
  const worst = Math.max(0, ...states.map((s) => s.assessment.riskScore));

  return (
    <>
      <ErrorBanner error={detection.error ?? open.error} />
      <div className="banner info">
        <span>
          <strong>Statistical, explainable detection.</strong> Baseline (mean ± std) per metric, z-score, EWMA control chart and a +20% significance gate.
          A <strong>detected anomaly</strong> means "unusual now". A <strong>forecast</strong> is a linear trend extrapolation with LOW confidence, not a trained prediction.
          Services without enough history are reported as <em>insufficient data</em> and are not scored.
        </span>
      </div>

      <div className="grid cols-4">
        <MetricCard label="Open incidents" value={openIncidents.length} tone={openIncidents.length ? "bad" : "ok"} sub="one per service at most" />
        <MetricCard label="Highest risk score" value={fmtNum(worst)} sub="0-100, weighted by metric importance" tone={worst >= 80 ? "bad" : worst >= 40 ? "warn" : "ok"} />
        <MetricCard label="Services evaluated" value={states.length} sub="every 10 s" tone="info" />
        <MetricCard label="Resolved (history)" value={(history.data ?? []).length} sub="auto-resolved after 6 clean checks" tone="muted" />
      </div>

      <Panel title="Detector state per service" right={states.some((s) => s.assessment.containsSyntheticData) ? <SyntheticBadge /> : undefined}>
        {states.length ? (
          <div style={{ overflowX: "auto" }}>
            <table>
              <thead><tr><th>Service</th><th>Status</th><th className="num">Risk</th><th>Anomalous metrics</th><th>Forecast</th><th>Notes</th></tr></thead>
              <tbody>{states.map(({ assessment: a }) => (
                <tr key={a.service}>
                  <td>{a.service}</td>
                  <td><Pill tone={STATUS_TONE[a.status]}>{a.status.replace("_", " ")}</Pill></td>
                  <td className="num">{fmtNum(a.riskScore)}</td>
                  <td className="small">{a.findings.filter((f) => f.anomalous).map((f) => f.metric).join(", ") || "-"}</td>
                  <td className="small">{a.forecasts.map((f) => `${f.metric} → ${f.targetPct}% in ~${fmtNum(f.etaMinutes)} min`).join("; ") || "-"}</td>
                  <td className="small muted">{a.notes[0] ?? ""}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        ) : <Empty>No services evaluated yet. Generate SYNTHETIC history: <span className="mono">scripts/simulate-telemetry.py --scenario normal --backfill-minutes 15</span></Empty>}
      </Panel>

      <div className="grid cols-21">
        <Panel title="Incidents">
          {[...openIncidents, ...(history.data ?? [])].length ? (
            <table>
              <thead><tr><th>Service</th><th>Type</th><th>Severity</th><th className="num">Risk</th><th>State</th><th>Opened</th></tr></thead>
              <tbody>{[...openIncidents, ...(history.data ?? [])].map((i) => (
                <tr key={i.id} onClick={() => setSelected(i.id)} style={{ cursor: "pointer", background: detail?.id === i.id ? "var(--panel-2)" : undefined }}>
                  <td>{i.service}</td><td><KindBadge kind={i.kind} /></td><td><SeverityBadge level={sev(i.severity)} /></td>
                  <td className="num">{fmtNum(i.riskScore)}</td><td><StatusPill status={i.status === "OPEN" ? "DEGRADED" : "UP"} /> <span className="small muted">{i.status}</span></td>
                  <td className="small muted">{ago(i.openedAt)}</td>
                </tr>
              ))}</tbody>
            </table>
          ) : <Empty>No incidents. Run <span className="mono">scripts/simulate-telemetry.py --scenario spike</span> to trigger one (synthetic).</Empty>}
        </Panel>

        <div className="muted small panel">Select an incident to see its evidence. Actions are proposed, approved and audited in the remediation panel below.</div>
      </div>

      {detail && <IncidentDetail incident={detail} />}
      <RemediationPanel />
    </>
  );
}

function IncidentDetail({ incident: i }: { incident: Incident }) {
  return (
    <Panel title={`${i.service} · ${i.status} · ${i.id.slice(0, 8)}`} right={<><KindBadge kind={i.kind} /><SeverityBadge level={sev(i.severity)} />{i.synthetic && <SyntheticBadge />}</>}>
      <p style={{ marginTop: 0 }}>{i.summary}</p>
      <p className="small muted">{i.kindExplanation}</p>
      <div className="grid cols-3">
        <div><div className="muted small">Risk now / peak</div><strong style={{ fontSize: 22 }}>{fmtNum(i.riskScore)}</strong><span className="muted"> / {fmtNum(i.peakRisk)}</span></div>
        <div><div className="muted small">Affected downstream (static model)</div>{i.affectedServices.length ? <ul style={{ margin: 0 }}>{i.affectedServices.map((a) => <li key={a}>{a}</li>)}</ul> : <span className="muted">none modelled</span>}</div>
        <div><div className="muted small">Opened / updated</div>{ago(i.openedAt)} / {ago(i.updatedAt)}</div>
      </div>
      <h2 style={{ margin: "14px 0 6px" }}>Evidence</h2>
      <div style={{ overflowX: "auto" }}>
        <table>
          <thead><tr><th>Metric</th><th className="num">Current</th><th className="num">Baseline</th><th className="num">z</th><th className="num">vs baseline</th><th className="num">EWMA / limit</th><th>Verdict</th></tr></thead>
          <tbody>{i.evidence.findings.map((f) => (
            <tr key={f.metric} title={f.explanation}>
              <td className="mono">{f.metric}</td><td className="num">{fmtNum(f.current, 1)}</td>
              <td className="num">{fmtNum(f.baselineMean, 1)} ± {fmtNum(f.baselineStd, 1)}</td><td className="num">{fmtNum(f.zScore, 1)}</td>
              <td className="num">{f.relativeDeviation >= 0 ? "+" : ""}{fmtNum(f.relativeDeviation * 100)}%</td>
              <td className="num">{fmtNum(f.ewma, 1)} / {fmtNum(f.ewmaUpperControlLimit, 1)}</td>
              <td>{f.anomalous ? <Pill tone="bad">anomalous</Pill> : <Pill tone="ok">normal</Pill>}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      {i.evidence.forecasts.length > 0 && (
        <ul>{i.evidence.forecasts.map((f) => <li key={f.metric} className="small">{f.explanation}</li>)}</ul>
      )}
      <div className="muted small" style={{ marginTop: 8 }}>Method: {i.evidence.method}. Hover a row for the full explanation.</div>
    </Panel>
  );
}
