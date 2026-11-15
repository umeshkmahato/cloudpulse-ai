import type { ReactNode } from "react";
import type { Tone } from "../format";

export function Panel({ title, right, children, className = "" }: { title?: ReactNode; right?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`panel ${className}`}>
      {(title || right) && (
        <div className="panel-head">
          <h2>{title}</h2>
          <div className="row">{right}</div>
        </div>
      )}
      {children}
    </section>
  );
}

export function MetricCard({ label, value, sub, tone = "muted", badge }: { label: string; value: ReactNode; sub?: ReactNode; tone?: Tone; badge?: ReactNode }) {
  return (
    <div className={`panel card ${tone}`} role="group" aria-label={label}>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div className="label">{label}</div>
        {badge}
      </div>
      <div className="value">{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  );
}

export function Pill({ tone = "muted", children, title }: { tone?: Tone | "sample" | "synthetic"; children: ReactNode; title?: string }) {
  return <span className={`pill ${tone}`} title={title}>{children}</span>;
}

export const StatusPill = ({ status }: { status: string }) => {
  const tone: Tone = status === "UP" || status === "CONFIRMED" || status === "RESERVED" ? "ok"
    : status === "DEGRADED" || status === "PENDING" ? "warn"
    : status === "STALE" || status === "REJECTED" ? "bad" : "muted";
  return <Pill tone={tone}><span className="dot" />{status}</Pill>;
};

export const SeverityBadge = ({ level }: { level: "critical" | "high" | "medium" | "low" }) => (
  <span className={`pill sev-${level}`}>{level.toUpperCase()}</span>
);

/** Marks data that is generated test data rather than real telemetry. */
export const SyntheticBadge = () => <Pill tone="synthetic" title="Generated test data, not real GCP telemetry">SYNTHETIC</Pill>;

/** Marks UI content that is illustrative until the backing phase is implemented. */
export const SampleBadge = ({ phase }: { phase: number }) => (
  <Pill tone="sample" title={`Illustrative only. Backend arrives in Phase ${phase}.`}>SAMPLE DATA · Phase {phase}</Pill>
);

export function SampleBanner({ phase, children }: { phase: number; children: ReactNode }) {
  return (
    <div className="banner sample" role="note">
      <SampleBadge phase={phase} />
      <div>{children}</div>
    </div>
  );
}

export function ErrorBanner({ error }: { error: string | null }) {
  if (!error) return null;
  return (
    <div className="banner error" role="alert">
      <strong>Backend unreachable or erroring.</strong>
      <span className="muted">{error}. Showing last known data; retrying automatically.</span>
    </div>
  );
}

export const Empty = ({ children }: { children: ReactNode }) => <div className="empty">{children}</div>;
