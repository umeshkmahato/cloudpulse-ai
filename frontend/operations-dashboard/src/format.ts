export const fmtNum = (n: number | null | undefined, digits = 0): string =>
  n === null || n === undefined || Number.isNaN(n) ? "-" : n.toLocaleString("en-US", { maximumFractionDigits: digits, minimumFractionDigits: digits });

export const fmtMoney = (n: number, currency = "USD"): string =>
  n.toLocaleString("en-US", { style: "currency", currency, maximumFractionDigits: 2 });

export function fmtMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return "-";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${(ms / 60_000).toFixed(1)} min`;
}

export const fmtTime = (iso: string): string => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleTimeString("en-GB", { hour12: false });
};

export function ago(iso: string, now = Date.now()): string {
  const s = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  return `${Math.floor(s / 3600)}h ago`;
}

/** Share of CONFIRMED-style outcomes (RESERVED) among all inventory outcomes; null when there are none. */
export function successRate(outcomes: { status: string; count: number }[]): number | null {
  const total = outcomes.reduce((a, o) => a + Number(o.count), 0);
  if (total === 0) return null;
  const ok = outcomes.filter((o) => o.status === "RESERVED").reduce((a, o) => a + Number(o.count), 0);
  return (ok / total) * 100;
}

export type Tone = "ok" | "warn" | "bad" | "info" | "muted";
export function toneForPct(v: number, warn = 70, bad = 85): Tone {
  return v >= bad ? "bad" : v >= warn ? "warn" : "ok";
}
