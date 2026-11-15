import { useState } from "react";
import { api } from "../api";
import { usePolling } from "../hooks";
import { useRefresh } from "../refresh";
import { useAuth } from "../auth";
import { ago, fmtNum } from "../format";
import type { RemediationView } from "../types";
import { Empty, Panel, Pill, SyntheticBadge } from "./ui";

const TONE: Record<string, "ok" | "warn" | "bad" | "info" | "muted"> = {
  PROPOSED: "warn", APPROVED: "info", EXECUTING: "info", MONITORING: "info", RECOVERED: "ok", NOT_RECOVERED: "bad", REJECTED: "muted", EXPIRED: "muted", BLOCKED: "bad", FAILED: "bad",
};

/** Operator console. Decisions are attributed to the logged-in user: the identity comes from the verified session token, never from a form field. */
export function RemediationPanel() {
  const { intervalMs } = useRefresh();
  const list = usePolling(() => api.remediations(), intervalMs);
  const status = usePolling(() => api.remediationStatus(), intervalMs);
  const { user, can } = useAuth();
  const mayDecide = can("OPERATOR");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const rows = list.data ?? [];
  const pending = rows.filter((r) => r.remediation.status === "PROPOSED");

  async function decide(id: string, verb: "approve" | "reject") {
    setBusy(id + verb); setMsg(null);
    try {
      await api.decide(id, verb, comment.trim());
      setMsg({ ok: true, text: verb === "approve" ? "Approved. The controller executes it on its next cycle (<= 10 s)." : "Rejected. Nothing was executed." });
      list.refresh();
    } catch (e) { setMsg({ ok: false, text: e instanceof Error ? e.message : String(e) }); }
    finally { setBusy(null); }
  }

  const s = status.data;
  return (
    <Panel title="Remediation approval" right={s ? <span className="row"><Pill tone={s.mode === "enforce" ? "warn" : "info"} title="dry-run changes nothing in Kubernetes">{s.mode.toUpperCase()}</Pill>{s.killSwitch && <Pill tone="bad">KILL SWITCH ON</Pill>}{!s.automatedPolicy.autoApprove && <Pill tone="muted">human approval required</Pill>}</span> : undefined}>
      {s && (
        <div className="small muted" style={{ marginBottom: 8 }}>
          Executable action: only <strong>scale</strong> of {Object.entries(s.allowList).map(([k, v]) => `${k} (${v.minReplicas}-${v.maxReplicas}, step ≤ ${v.maxStep})`).join(", ")}. Cooldown {s.cooldownMinutes} min, max {s.maxActionsPerHour}/hour.
          No shell, no other API calls; the AI assistant has no path to this controller.
        </div>
      )}
      {list.error && <div className="banner error" role="alert">Remediation controller unreachable: {list.error}</div>}

      {pending.length > 0 && (
        <div className="panel" style={{ background: "var(--bg)", marginBottom: 10 }}>
          <div className="row">
            <span className="small muted">Acting as</span><strong>{user?.username}</strong>
            {!mayDecide && <Pill tone="warn">read-only: approving needs the operator role</Pill>}
            <label className="small muted" htmlFor="cm">Comment</label>
            <input id="cm" value={comment} maxLength={300} onChange={(e) => setComment(e.target.value)} placeholder="why" style={{ width: 240 }} />
          </div>
        </div>
      )}
      {msg && <div className={`banner ${msg.ok ? "info" : "error"}`} role={msg.ok ? "status" : "alert"} style={{ marginBottom: 8 }}>{msg.text}</div>}

      {rows.length === 0 ? <Empty>No remediation proposals. They appear when an incident recommends scaling an allow-listed service.</Empty> : (
        <div style={{ overflowX: "auto" }}>
          <table>
            <thead><tr><th>Service</th><th>Action</th><th>Status</th><th>Decision</th><th>Result</th><th /></tr></thead>
            <tbody>
              {rows.map((v) => <Row key={v.remediation.id} v={v} open={open === v.remediation.id} onToggle={() => setOpen(open === v.remediation.id ? null : v.remediation.id)}
                canDecide={mayDecide} busy={busy} onDecide={decide} />)}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

function Row({ v, open, onToggle, canDecide, busy, onDecide }: { v: RemediationView; open: boolean; onToggle: () => void; canDecide: boolean; busy: string | null; onDecide: (id: string, verb: "approve" | "reject") => void }) {
  const r = v.remediation;
  const audit = usePolling(() => (open ? api.remediationAudit(r.id) : Promise.resolve([])), 0, [open, r.status]);
  return (
    <>
      <tr style={{ cursor: "pointer" }} onClick={onToggle} aria-expanded={open}>
        <td>{r.service}{r.synthetic && <> <SyntheticBadge /></>}</td>
        <td>{r.toReplicas > 0 ? `raise floor ${r.fromReplicas} → ${r.toReplicas}` : "-"}</td>
        <td><Pill tone={TONE[r.status] ?? "muted"}>{r.status}</Pill></td>
        <td className="small">{r.decidedBy ?? (r.status === "PROPOSED" ? `expires ${ago(r.expiresAt)}`.replace("ago", "") : "-")}</td>
        <td className="small">{r.timeToRecoverSeconds != null ? `recovered in ${fmtNum(r.timeToRecoverSeconds)} s` : r.observedReplicas != null && r.status === "MONITORING" ? `${r.observedReplicas}/${r.toReplicas} ready` : v.nextStep}</td>
        <td onClick={(e) => e.stopPropagation()}>
          {r.status === "PROPOSED" && (
            <span className="row">
              <button className="primary" disabled={!canDecide || busy !== null} title={canDecide ? "" : "requires the operator role"} onClick={() => onDecide(r.id, "approve")}>Approve</button>
              <button disabled={!canDecide || busy !== null} onClick={() => onDecide(r.id, "reject")}>Reject</button>
            </span>
          )}
        </td>
      </tr>
      {open && (
        <tr><td colSpan={6}>
          <div className="small"><strong>Why:</strong> {r.rationale}</div>
          <div className="small"><strong>Guardrails:</strong> {v.guardrails.join(" · ")}</div>
          <div className="small muted" style={{ marginTop: 4 }}><strong>Audit trail (append-only)</strong></div>
          <ul className="small mono" style={{ margin: "2px 0 0", paddingLeft: 16 }}>
            {(audit.data ?? []).map((a) => <li key={a.id}>{new Date(a.at).toLocaleTimeString("en-GB")} · {a.actor} · <strong>{a.event}</strong> · {a.details}</li>)}
          </ul>
        </td></tr>
      )}
    </>
  );
}
