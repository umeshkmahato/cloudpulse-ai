import { useState } from "react";
import { api } from "../api";
import { Bars, TimeSeries } from "../components/charts";
import { EventStream } from "../components/EventStream";
import { Empty, ErrorBanner, MetricCard, Panel, StatusPill, SyntheticBadge } from "../components/ui";
import { fmtMoney, fmtMs, fmtNum, successRate } from "../format";
import { usePolling } from "../hooks";
import { useRefresh } from "../refresh";
import { useAuth } from "../auth";
import type { OrderView } from "../types";

const WINDOWS = [15, 60, 240];

export default function Executive() {
  const { intervalMs } = useRefresh();
  const [minutes, setMinutes] = useState(60);
  const bucket = minutes <= 15 ? 30 : minutes <= 60 ? 60 : 300;

  const overview = usePolling(() => api.overview(minutes), intervalMs, [minutes]);
  const trend = usePolling(() => api.orderTrend(minutes, bucket), intervalMs, [minutes]);
  const outcomes = usePolling(() => api.outcomes(minutes), intervalMs, [minutes]);
  const health = usePolling(() => api.serviceHealth(), intervalMs);
  const latency = usePolling(() => api.latency(minutes), intervalMs, [minutes]);
  const events = usePolling(() => api.recentEvents(25), intervalMs);

  const o = overview.data;
  const rate = o ? successRate(o.inventoryOutcomes) : null;
  const services = health.data ?? [];
  const degraded = services.filter((s) => s.status !== "UP").length;
  const worstP95 = Math.max(0, ...(latency.data ?? []).filter((l) => l.type !== "inventory.updated").map((l) => l.p95_ms));
  const error = overview.error ?? trend.error ?? health.error;

  return (
    <>
      <ErrorBanner error={error} />
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div className="row">
          <span className="muted small">Window</span>
          {WINDOWS.map((w) => (
            <button key={w} className={w === minutes ? "primary" : ""} onClick={() => setMinutes(w)} aria-pressed={w === minutes}>
              {w >= 60 ? `${w / 60} h` : `${w} min`}
            </button>
          ))}
        </div>
        <span className="muted small">Source: {o?.dataSource ?? "…"}</span>
      </div>

      <div className="grid cols-4">
        <MetricCard label="Orders" value={fmtNum(o?.orders.orders)} sub={`last ${minutes} min`} tone="info" />
        <MetricCard label="Order value" value={o ? fmtMoney(Number(o.orders.order_value)) : "-"} sub="sum of accepted orders" tone="info" />
        <MetricCard
          label="Reservation success"
          value={rate === null ? "-" : `${fmtNum(rate, 1)}%`}
          sub="inventory RESERVED vs REJECTED"
          tone={rate === null ? "muted" : rate >= 95 ? "ok" : rate >= 80 ? "warn" : "bad"}
        />
        <MetricCard
          label="Services healthy"
          value={services.length ? `${services.length - degraded}/${services.length}` : "-"}
          sub={services.length ? "rule-based thresholds (not anomaly detection)" : "no telemetry yet"}
          tone={!services.length ? "muted" : degraded ? "warn" : "ok"}
          badge={services.some((s) => s.containsSyntheticData) ? <SyntheticBadge /> : undefined}
        />
      </div>

      <div className="grid cols-21">
        <Panel title="Orders over time">
          <TimeSeries data={(trend.data ?? []) as unknown as Record<string, unknown>[]} series={[{ key: "orders", label: "Orders", color: "#38bdf8" }]} area />
        </Panel>
        <Panel title="Inventory outcomes">
          {(outcomes.data ?? []).length ? (
            <table>
              <thead><tr><th>Status</th><th>Reason</th><th className="num">Count</th></tr></thead>
              <tbody>
                {outcomes.data!.map((r) => (
                  <tr key={`${r.status}-${r.reason}`}><td><StatusPill status={r.status} /></td><td className="small">{r.reason || "-"}</td><td className="num">{r.count}</td></tr>
                ))}
              </tbody>
            </table>
          ) : <Empty>No outcomes yet.</Empty>}
        </Panel>
      </div>

      <div className="grid cols-3">
        <Panel title="Service health" className="">
          {services.length ? (
            <table>
              <tbody>
                {services.map((s) => (
                  <tr key={s.service}>
                    <td>{s.service}</td>
                    <td><StatusPill status={s.status} /></td>
                    <td className="small muted">{s.reasons[0] ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : <Empty>No telemetry. Run <span className="mono">scripts/simulate-telemetry.py</span> (synthetic).</Empty>}
        </Panel>
        <Panel title="Event pipeline latency (p95)">
          <div className="value" style={{ fontSize: 26, fontWeight: 700 }}>{worstP95 ? fmtMs(worstP95) : "-"}</div>
          <div className="muted small" style={{ marginBottom: 8 }}>event time → analytics store (orders, metrics)</div>
          <Bars
            height={120}
            data={(latency.data ?? []).map((l) => ({ type: l.type.split(".")[0], p50: Math.round(l.p50_ms), p95: Math.round(l.p95_ms) }))}
            xKey="type"
            series={[{ key: "p50", label: "p50 ms", color: "#34d399" }, { key: "p95", label: "p95 ms", color: "#fbbf24" }]}
          />
          <div className="muted small">Inventory p95 can include drained backlog.</div>
        </Panel>
        <TestOrderPanel />
      </div>

      <Panel title="Live event stream" right={<span className="muted small">business events · newest first</span>}>
        <EventStream events={events.data ?? []} />
      </Panel>
    </>
  );
}

function TestOrderPanel() {
  const { can } = useAuth();
  const allowed = can("OPERATOR");
  const stock = usePolling(() => api.stock(), 0);
  const [sku, setSku] = useState("SKU-RED-1");
  const [qty, setQty] = useState(1);
  const [busy, setBusy] = useState(false);
  const [order, setOrder] = useState<OrderView | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function place() {
    setBusy(true); setErr(null); setOrder(null);
    try {
      const created = await api.placeOrder(sku, qty, 10);
      setOrder(created);
      for (let i = 0; i < 15; i++) {            // follow the async lifecycle: PENDING -> CONFIRMED/REJECTED
        await new Promise((r) => setTimeout(r, 1000));
        const cur = await api.order(created.orderId);
        setOrder(cur);
        if (cur.status !== "PENDING") break;
      }
      stock.refresh();
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }

  return (
    <Panel title="Place test order" right={<span className="muted small">drives the real flow</span>}>
      <div className="row">
        <label className="sr-only" htmlFor="sku">SKU</label>
        <select id="sku" value={sku} onChange={(e) => setSku(e.target.value)} disabled={busy}>
          {(stock.data ?? []).length === 0 && <option value={sku}>{sku}</option>}
          {(stock.data ?? []).map((s) => (
            <option key={s.sku} value={s.sku}>{s.sku} ({s.available} in stock)</option>
          ))}
        </select>
        <label className="sr-only" htmlFor="qty">Quantity</label>
        <input id="qty" type="number" min={1} max={1000} value={qty} onChange={(e) => setQty(Math.max(1, Number(e.target.value) || 1))} style={{ width: 80 }} disabled={busy} />
        <button className="primary" onClick={place} disabled={busy || !allowed} title={allowed ? "" : "Placing orders requires the operator role"}>{busy ? "Processing…" : "Submit order"}</button>
      </div>
      {err && <div className="banner error" role="alert" style={{ marginTop: 10 }}>{err}</div>}
      {order && (
        <div style={{ marginTop: 10 }} aria-live="polite">
          <div className="mono small">{order.orderId}</div>
          <div className="row"><StatusPill status={order.status} />{order.statusReason && <span className="small muted">{order.statusReason}</span>}</div>
          <div className="muted small">Order → Postgres → Pub/Sub → Inventory → Pub/Sub → Order</div>
        </div>
      )}
    </Panel>
  );
}
