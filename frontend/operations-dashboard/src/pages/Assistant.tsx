import { useEffect, useRef, useState } from "react";
import { api } from "../api";
import { Panel, Pill, SyntheticBadge } from "../components/ui";
import type { AssistantAnswer, Evidence } from "../types";

const FALLBACK = [
  "Why is the order service slow?", "Which service is responsible?", "What is the likely root cause?", "What is the business impact?",
  "What happened in the last 10 minutes?", "What corrective action is recommended?", "What is the estimated infrastructure cost impact?",
  "What happens if the inventory service fails?",
];
const TONE = { HIGH: "ok", MEDIUM: "info", LOW: "warn", NONE: "muted" } as const;

interface Turn { question: string; answer?: AssistantAnswer; error?: string }

export default function Assistant() {
  const [turns, setTurns] = useState<Turn[]>([]);
  const [q, setQ] = useState("");
  const [busy, setBusy] = useState(false);
  const [suggested, setSuggested] = useState<string[]>(FALLBACK);
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => { api.suggestedQuestions().then(setSuggested).catch(() => setSuggested(FALLBACK)); }, []);
  useEffect(() => { end.current?.scrollIntoView?.({ behavior: "smooth", block: "end" }); }, [turns]);

  async function ask(question: string) {
    const text = question.trim();
    if (!text || busy) return;
    setBusy(true); setQ("");
    setTurns((t) => [...t, { question: text }]);
    try {
      const answer = await api.ask(text);
      setTurns((t) => t.map((x, i) => (i === t.length - 1 ? { ...x, answer } : x)));
    } catch (e) {
      setTurns((t) => t.map((x, i) => (i === t.length - 1 ? { ...x, error: e instanceof Error ? e.message : String(e) } : x)));
    } finally { setBusy(false); }
  }

  return (
    <>
      <div className="banner info">
        <span><strong>Evidence-grounded.</strong> Answers are built only from operational data (incidents, detector state, twin, analytics). Every statement cites evidence ids you can open below;
          causes are labelled hypotheses; where evidence is missing the assistant says so. It is read-only and never runs commands.</span>
      </div>
      <div className="grid cols-21">
        <Panel title="Conversation">
          <div className="chat" aria-live="polite">
            {turns.length === 0 && <div className="empty">Pick a question on the right or type your own.</div>}
            {turns.map((t, i) => (
              <div key={i} className="chat">
                <div className="bubble user">{t.question}</div>
                {t.error && <div className="banner error" role="alert">{t.error}. Is the assistant service (port 8085) running?</div>}
                {!t.answer && !t.error && <div className="bubble muted">Gathering evidence…</div>}
                {t.answer && <AnswerCard a={t.answer} />}
              </div>
            ))}
            <div ref={end} />
          </div>
          <form className="row" style={{ marginTop: 12 }} onSubmit={(e) => { e.preventDefault(); void ask(q); }}>
            <label className="sr-only" htmlFor="ask">Ask the incident assistant</label>
            <input id="ask" value={q} maxLength={500} onChange={(e) => setQ(e.target.value)} placeholder="Ask about an incident…" style={{ flex: 1, minWidth: 200 }} disabled={busy} />
            <button className="primary" disabled={busy || !q.trim()}>{busy ? "Working…" : "Ask"}</button>
          </form>
        </Panel>
        <Panel title="Questions it can answer">
          <ul style={{ paddingLeft: 18 }}>
            {suggested.map((s) => (<li key={s}><button style={{ border: "none", background: "none", color: "var(--accent)", padding: "3px 0", textAlign: "left" }} onClick={() => void ask(s)} disabled={busy}>{s}</button></li>))}
          </ul>
        </Panel>
      </div>
    </>
  );
}

function AnswerCard({ a }: { a: AssistantAnswer }) {
  const [open, setOpen] = useState<string | null>(null);
  const byId = new Map(a.evidence.map((e) => [e.id, e]));
  const headline = a.answer.split("\n")[0];
  return (
    <div className="bubble">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <strong>{headline}</strong>
        <span className="row">
          <Pill tone={TONE[a.confidence]} title="How strongly the evidence supports this answer">{a.confidence} confidence</Pill>
          {a.containsSyntheticData && <SyntheticBadge />}
        </span>
      </div>
      {a.claims.length > 0 && (
        <ul style={{ paddingLeft: 18 }}>
          {a.claims.map((c, i) => (
            <li key={i} className={c.text.startsWith("Hypothesis") ? "muted" : undefined}>
              {c.text}{" "}
              {c.evidenceIds.map((id) => (
                <button key={id} className="mono small" style={{ padding: "0 5px", marginRight: 3 }} onClick={() => setOpen(open === id ? null : id)} aria-expanded={open === id} title={byId.get(id)?.summary}>{id}</button>
              ))}
            </li>
          ))}
        </ul>
      )}
      {open && byId.get(open) && <EvidenceView e={byId.get(open)!} />}
      {a.limitations.length > 0 && (
        <details><summary className="small muted">Limitations ({a.limitations.length})</summary><ul className="small muted">{a.limitations.map((l) => <li key={l}>{l}</li>)}</ul></details>
      )}
      <div className="small muted">Answer by: {a.provider} · {a.evidence.length} evidence items</div>
    </div>
  );
}

function EvidenceView({ e }: { e: Evidence }) {
  return (
    <div className="panel" style={{ background: "var(--bg)", marginTop: 8 }}>
      <div className="row small"><strong className="mono">{e.id}</strong><Pill tone="info">{e.type}</Pill>{e.synthetic && <SyntheticBadge />}<span className="muted">{e.summary}</span></div>
      <div className="small muted mono">source: {e.source}</div>
      <pre className="mono small" style={{ maxHeight: 180, overflow: "auto", margin: "6px 0 0" }}>{JSON.stringify(e.data, null, 2)}</pre>
    </div>
  );
}
