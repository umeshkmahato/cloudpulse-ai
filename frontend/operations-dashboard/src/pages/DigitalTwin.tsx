import { useMemo, useState } from "react";
import { api } from "../api";
import { Empty, ErrorBanner, Panel, Pill } from "../components/ui";
import { usePolling } from "../hooks";
import { useRefresh } from "../refresh";
import type { TwinImpact } from "../types";

const COLORS = { failed: "#f87171", hard: "#fb923c", delayed: "#fbbf24", ok: "#34d399", planned: "#5b7194" } as const;
const LIVE: Record<string, "ok" | "warn" | "bad" | "muted"> = { OK: "ok", WATCH: "ok", ANOMALY: "bad", FORECAST: "warn", STALE: "muted", INSUFFICIENT_DATA: "muted", UNKNOWN: "muted" };

export default function DigitalTwin() {
  const { intervalMs } = useRefresh();
  const [scope, setScope] = useState<"current" | "target">("current");
  const [failed, setFailed] = useState<string | null>("inventory-service");
  const graph = usePolling(() => api.twinGraph(), intervalMs);
  const impact = usePolling<TwinImpact | null>(() => (failed ? api.twinImpact(failed, scope) : Promise.resolve(null)), 0, [failed, scope]);

  const g = graph.data;
  const byId = useMemo(() => new Map((g?.nodes ?? []).map((n) => [n.component.id, n])), [g]);
  const hard = new Set((impact.data?.hardImpacted ?? []).map((r) => r.id));
  const delayed = new Set((impact.data?.delayedImpacted ?? []).map((r) => r.id));
  const visible = (g?.nodes ?? []).filter((n) => scope === "target" || n.component.deploymentStatus === "ACTIVE_LOCAL");
  const visibleIds = new Set(visible.map((n) => n.component.id));
  const edges = (g?.edges ?? []).filter((e) => e.relation === "NEEDS" && visibleIds.has(e.fromId) && visibleIds.has(e.toId));
  const stateOf = (id: string, planned: boolean) => id === failed ? "failed" : hard.has(id) ? "hard" : delayed.has(id) ? "delayed" : planned ? "planned" : "ok";

  return (
    <>
      <ErrorBanner error={graph.error ?? impact.error} />
      <div className="banner info">
        <span>
          Relational digital twin served by the Operations service (no graph database). Structure comes from tables; the dot on each service is its <strong>live</strong> detector status.
          Components marked PLANNED are part of the target architecture and are <strong>not deployed</strong>. Failure analysis is a structural what-if, not a load simulation.
        </span>
      </div>
      <div className="row">
        <span className="muted small">Scope</span>
        {(["current", "target"] as const).map((s) => <button key={s} className={s === scope ? "primary" : ""} aria-pressed={s === scope} onClick={() => setScope(s)}>{s === "current" ? "Deployed now" : "Target architecture"}</button>)}
      </div>
      <div className="grid cols-21">
        <Panel title="Dependency graph" right={<span className="muted small">click a component to simulate its failure</span>} className="graph">
          {g ? (
            <svg viewBox={`0 0 900 ${scope === "target" ? 580 : 470}`} role="group" aria-label="Service dependency graph">
              <defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#5b7194" /></marker></defs>
              {visible.filter((n) => n.component.parentId && visibleIds.has(n.component.parentId)).map((n) => {
                const p = byId.get(n.component.parentId!)!.component, c = n.component;
                return <line key={`in-${c.id}`} x1={p.uiX} y1={p.uiY} x2={c.uiX} y2={c.uiY} stroke="#26365a" strokeWidth={1} strokeDasharray="1 5" />;
              })}
              {edges.map((e) => {
                const a = byId.get(e.fromId)!.component, b = byId.get(e.toId)!.component;
                return <line key={`${e.fromId}-${e.toId}`} x1={a.uiX} y1={a.uiY} x2={b.uiX} y2={b.uiY} stroke="#3a4d70" strokeWidth={1.5} strokeDasharray={e.mode === "ASYNC" ? "5 4" : undefined} markerEnd="url(#arr)" />;
              })}
              {visible.map((n) => {
                const c = n.component, st = stateOf(c.id, c.deploymentStatus !== "ACTIVE_LOCAL");
                const live = LIVE[n.liveStatus];
                return (
                  <g key={c.id} tabIndex={0} role="button" aria-label={`${c.name}, ${st}${n.liveStatus !== "N/A" ? `, live ${n.liveStatus}` : ""}`} style={{ cursor: "pointer" }}
                     onClick={() => setFailed(c.id === failed ? null : c.id)} onKeyDown={(ev) => (ev.key === "Enter" || ev.key === " ") && setFailed(c.id === failed ? null : c.id)}>
                    <rect x={c.uiX - 62} y={c.uiY - 20} width={124} height={40} rx={c.kind === "TOPIC" ? 18 : c.kind === "DATABASE" ? 6 : 10} fill="#111a2e" stroke={COLORS[st]} strokeWidth={failed ? 2.5 : 1.5} strokeDasharray={c.deploymentStatus !== "ACTIVE_LOCAL" ? "4 3" : undefined} />
                    <text x={c.uiX} y={c.uiY + 4} textAnchor="middle">{c.name.length > 20 ? c.name.slice(0, 19) + "…" : c.name}</text>
                    {live && <circle cx={c.uiX + 56} cy={c.uiY - 14} r={5} fill={live === "bad" ? "#f87171" : live === "warn" ? "#fbbf24" : live === "ok" ? "#34d399" : "#5b7194"}><title>live: {n.liveStatus}{n.riskScore != null ? `, risk ${n.riskScore}` : ""}</title></circle>}
                  </g>
                );
              })}
            </svg>
          ) : <Empty>Loading twin…</Empty>}
          <div className="row small muted" style={{ marginTop: 8 }}>
            <Pill tone="bad">failed</Pill><Pill tone="warn">hard impact (sync)</Pill><Pill tone="synthetic">delayed (buffered)</Pill><Pill tone="ok">unaffected</Pill><Pill tone="muted">planned / not deployed</Pill>
            <span>Solid = synchronous · dashed = asynchronous · dotted = belongs to application</span>
          </div>
        </Panel>

        <Panel title={failed ? `What happens if ${failed} fails?` : "Select a component"}>
          {impact.data ? <ImpactView r={impact.data} /> : <Empty>{failed ? "Analysing…" : "Pick a node in the graph."}</Empty>}
        </Panel>
      </div>
    </>
  );
}

function ImpactView({ r }: { r: TwinImpact }) {
  const list = (a: { name: string }[]) => (a.length ? a.map((x) => x.name).join(", ") : "none");
  return (
    <>
      <p style={{ marginTop: 0 }}><strong>Business impact:</strong> {r.businessImpact}</p>
      {r.notes.map((n) => <div key={n} className="banner info small">{n}</div>)}
      <div className="small muted">Hard-impacted (synchronous dependents)</div><p>{list(r.hardImpacted)}</p>
      <div className="small muted">Delayed (buffered by Pub/Sub, not lost)</div><p>{list(r.delayedImpacted)}</p>
      <div className="small muted">Impacted transactions</div>
      <ul>{r.transactions.length ? r.transactions.map((t) => <li key={t.id}><Pill tone={t.status === "BLOCKED" ? "bad" : "warn"}>{t.status}</Pill> {t.name}{t.revenueImpacting ? " (revenue)" : ""}<div className="small muted">{t.businessImpact}</div></li>) : <li className="muted">none</li>}</ul>
      {r.messageRecovery.length > 0 && (<><div className="small muted">Message recovery</div><ul>{r.messageRecovery.map((s) => <li key={s.text}>{s.text}</li>)}</ul></>)}
      {r.recovery.length > 0 && (<><div className="small muted">Recovery</div><ul>{r.recovery.map((s) => <li key={s.text}>{s.text}</li>)}</ul></>)}
      {r.mitigation.length > 0 && (<><div className="small muted">Mitigation</div><ul>{r.mitigation.map((s) => <li key={s.text}>{s.text}</li>)}</ul></>)}
    </>
  );
}
