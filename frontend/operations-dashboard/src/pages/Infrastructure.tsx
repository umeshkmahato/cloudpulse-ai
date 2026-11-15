import { useMemo, useState } from "react";
import { api } from "../api";
import { TimeSeries } from "../components/charts";
import { useMetricSeries } from "../components/useMetricSeries";
import { Empty, ErrorBanner, MetricCard, Panel, StatusPill, SyntheticBadge } from "../components/ui";
import { fmtNum, toneForPct } from "../format";
import { usePolling } from "../hooks";
import { useRefresh } from "../refresh";
import type { LatestSample } from "../types";

const METRICS = ["cpu_pct", "memory_pct", "db_connections_pct"];

export function latestOf(samples: LatestSample[], service: string, metric: string): LatestSample | undefined {
  return samples.find((s) => s.service === service && s.metric === metric);
}

export default function Infrastructure() {
  const { intervalMs } = useRefresh();
  const [minutes, setMinutes] = useState(15);
  const health = usePolling(() => api.serviceHealth(), intervalMs);
  const latest = usePolling(() => api.latestMetrics(), intervalMs);
  const services = useMemo(() => (health.data ?? []).map((s) => s.service), [health.data]);
  const [picked, setPicked] = useState<string>("");
  const service = picked || services[0] || "order-service";
  const series = useMetricSeries(service, METRICS, minutes, minutes <= 15 ? 10 : 60, intervalMs);

  const lm = latest.data ?? [];
  const cpu = latestOf(lm, service, "cpu_pct");
  const mem = latestOf(lm, service, "memory_pct");
  const db = latestOf(lm, service, "db_connections_pct");
  const restarts = latestOf(lm, service, "pod_restarts");
  const sel = health.data?.find((s) => s.service === service);
  const synthetic = [cpu, mem, db].some((m) => m?.synthetic);

  return (
    <>
      <ErrorBanner error={health.error ?? latest.error} />
      {!services.length && !health.loading && (
        <div className="banner info">No telemetry has been received yet. Generate synthetic data with{" "}
          <code className="mono">scripts/simulate-telemetry.py --scenario normal --backfill-minutes 15</code>.</div>
      )}
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div className="row">
          <label className="muted small" htmlFor="svc">Service</label>
          <select id="svc" value={service} onChange={(e) => setPicked(e.target.value)}>
            {(services.length ? services : [service]).map((s) => <option key={s}>{s}</option>)}
          </select>
          {sel && <StatusPill status={sel.status} />}
          {synthetic && <SyntheticBadge />}
        </div>
        <div className="row">
          {[15, 60, 240].map((w) => (
            <button key={w} className={w === minutes ? "primary" : ""} onClick={() => setMinutes(w)} aria-pressed={w === minutes}>{w >= 60 ? `${w / 60} h` : `${w} min`}</button>
          ))}
        </div>
      </div>

      <div className="grid cols-4">
        <MetricCard label="CPU utilization" value={cpu ? `${fmtNum(cpu.value, 1)}%` : "-"} tone={cpu ? toneForPct(cpu.value) : "muted"} sub="latest sample" />
        <MetricCard label="Memory utilization" value={mem ? `${fmtNum(mem.value, 1)}%` : "-"} tone={mem ? toneForPct(mem.value) : "muted"} sub="latest sample" />
        <MetricCard label="DB connections" value={db ? `${fmtNum(db.value, 1)}%` : "-"} tone={db ? toneForPct(db.value, 75, 90) : "muted"} sub="of pool limit" />
        <MetricCard label="Pod restarts" value={restarts ? fmtNum(restarts.value) : "-"} tone={restarts && restarts.value > 0 ? "warn" : "muted"} sub="latest sample" />
      </div>

      <Panel title={`Resource utilization · ${service}`} right={<span className="muted small">avg per bucket</span>}>
        {series.data?.rows.length ? (
          <TimeSeries
            data={series.data.rows}
            unit="%"
            series={[
              { key: "cpu_pct", label: "CPU %", color: "#f87171" },
              { key: "memory_pct", label: "Memory %", color: "#818cf8" },
              { key: "db_connections_pct", label: "DB connections %", color: "#fbbf24" },
            ]}
          />
        ) : <Empty>No samples in this window.</Empty>}
      </Panel>

      {sel && sel.reasons.length > 0 && (
        <Panel title="Why is this service not healthy?">
          <ul>{sel.reasons.map((r) => <li key={r}>{r}</li>)}</ul>
          <div className="muted small">Static thresholds today. Phase 9 adds baselines and anomaly scoring.</div>
        </Panel>
      )}
    </>
  );
}
