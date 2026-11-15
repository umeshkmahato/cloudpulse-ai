import { useMemo, useState } from "react";
import { api } from "../api";
import { TimeSeries } from "../components/charts";
import { useMetricSeries } from "../components/useMetricSeries";
import { Empty, ErrorBanner, Panel, SyntheticBadge } from "../components/ui";
import { fmtMs, fmtNum } from "../format";
import { usePolling } from "../hooks";
import { useRefresh } from "../refresh";

const M = ["request_count", "latency_ms", "error_rate_pct", "event_latency_ms"];

export default function Performance() {
  const { intervalMs } = useRefresh();
  const [minutes, setMinutes] = useState(15);
  const health = usePolling(() => api.serviceHealth(), intervalMs);
  const services = useMemo(() => (health.data ?? []).map((s) => s.service), [health.data]);
  const [picked, setPicked] = useState("");
  const service = picked || services[0] || "order-service";
  const s = useMetricSeries(service, M, minutes, minutes <= 15 ? 10 : 60, intervalMs);
  const latency = usePolling(() => api.latency(minutes), intervalMs, [minutes]);
  const rows = s.data?.rows ?? [];

  return (
    <>
      <ErrorBanner error={health.error ?? s.error} />
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div className="row">
          <label className="muted small" htmlFor="psvc">Service</label>
          <select id="psvc" value={service} onChange={(e) => setPicked(e.target.value)}>
            {(services.length ? services : [service]).map((x) => <option key={x}>{x}</option>)}
          </select>
          {s.data?.synthetic && <SyntheticBadge />}
        </div>
        <div className="row">
          {[15, 60, 240].map((w) => (
            <button key={w} className={w === minutes ? "primary" : ""} onClick={() => setMinutes(w)} aria-pressed={w === minutes}>{w >= 60 ? `${w / 60} h` : `${w} min`}</button>
          ))}
        </div>
      </div>

      <div className="grid cols-2">
        <Panel title="Request rate (rpm)"><TimeSeries data={rows} series={[{ key: "request_count", label: "Requests/min", color: "#38bdf8" }]} area /></Panel>
        <Panel title="API latency (ms)"><TimeSeries data={rows} unit="ms" series={[{ key: "latency_ms", label: "Latency ms", color: "#fbbf24" }]} /></Panel>
        <Panel title="Error rate (%)"><TimeSeries data={rows} unit="%" series={[{ key: "error_rate_pct", label: "Error rate %", color: "#f87171" }]} /></Panel>
        <Panel title="Event processing latency (ms)"><TimeSeries data={rows} unit="ms" series={[{ key: "event_latency_ms", label: "Event latency ms", color: "#818cf8" }]} /></Panel>
      </div>

      <Panel title="Pipeline latency by event type" right={<span className="muted small">measured, not synthetic: event time → analytics store</span>}>
        {(latency.data ?? []).length ? (
          <table>
            <thead><tr><th>Event type</th><th className="num">Events</th><th className="num">p50</th><th className="num">p95</th></tr></thead>
            <tbody>
              {latency.data!.map((l) => (
                <tr key={l.type}><td className="mono">{l.type}</td><td className="num">{fmtNum(l.events)}</td><td className="num">{fmtMs(l.p50_ms)}</td><td className="num">{fmtMs(l.p95_ms)}</td></tr>
              ))}
            </tbody>
          </table>
        ) : <Empty>No events in this window.</Empty>}
      </Panel>
    </>
  );
}
