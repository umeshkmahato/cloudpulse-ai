import type { AssistantAnswer, AuditRow, ControllerStatus, RemediationView, CurrentState, TwinGraph, TwinImpact, Incident, LatencyRow, LatestSample, MetricBucket, OrderBucket, OrderView, Outcome, Overview, ServiceHealth, StockItem, StreamEvent } from "./types";

const CSRF = { "X-CloudPulse-CSRF": "1" };   // required by the gateway on every state-changing call (defence in depth with SameSite=Strict)

/** A 401 from any API means the session expired: tell the app to show the login page. */
function unauthorized(res: Response) {
  if (res.status === 401) window.dispatchEvent(new Event("cp:unauthorized"));
}

async function get<T>(url: string): Promise<T> {
  const res = await fetch(url, { headers: { Accept: "application/json" }, credentials: "same-origin" });
  unauthorized(res);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return res.json() as Promise<T>;
}

const q = (o: Record<string, string | number | boolean>) =>
  new URLSearchParams(Object.entries(o).map(([k, v]) => [k, String(v)])).toString();

export const api = {
  overview: (minutes: number) => get<Overview>(`/ops-api/api/v1/analytics/overview?${q({ minutes })}`),
  orderTrend: (minutes: number, bucketSeconds: number) =>
    get<OrderBucket[]>(`/ops-api/api/v1/analytics/orders/trend?${q({ minutes, bucketSeconds })}`),
  outcomes: (minutes: number) => get<Outcome[]>(`/ops-api/api/v1/analytics/inventory/outcomes?${q({ minutes })}`),
  metricTrend: (service: string, metric: string, minutes: number, bucketSeconds: number) =>
    get<MetricBucket[]>(`/ops-api/api/v1/analytics/metrics/trend?${q({ service, metric, minutes, bucketSeconds })}`),
  latency: (minutes: number) => get<LatencyRow[]>(`/ops-api/api/v1/analytics/pipeline/latency?${q({ minutes })}`),
  recentEvents: (limit: number) => get<StreamEvent[]>(`/ops-api/api/v1/analytics/events/recent?${q({ limit })}`),
  incidents: (status?: "OPEN" | "RESOLVED") => get<Incident[]>(`/ops-api/api/v1/incidents?${q({ limit: 50, ...(status ? { status } : {}) })}`),
  detection: () => get<CurrentState[]>(`/ops-api/api/v1/detection/current`),
  twinGraph: () => get<TwinGraph>(`/ops-api/api/v1/twin/graph`),
  twinImpact: (component: string, scope: "current" | "target") => get<TwinImpact>(`/ops-api/api/v1/twin/impact?${q({ component, scope })}`),
  suggestedQuestions: () => get<string[]>(`/assistant-api/api/v1/assistant/questions`),
  async ask(question: string): Promise<AssistantAnswer> {
    const res = await fetch(`/assistant-api/api/v1/assistant/ask`, {
      method: "POST", headers: { "Content-Type": "application/json", ...CSRF }, credentials: "same-origin", body: JSON.stringify({ question }),
    });
    unauthorized(res);
    if (!res.ok) throw new Error(`Assistant error ${res.status}`);
    return res.json();
  },
  remediations: () => get<RemediationView[]>(`/remediation-api/api/v1/remediations?limit=30`),
  remediationStatus: () => get<ControllerStatus>(`/remediation-api/api/v1/remediation/status`),
  remediationAudit: (id: string) => get<AuditRow[]>(`/remediation-api/api/v1/remediations/${encodeURIComponent(id)}/audit`),
  async decide(id: string, verb: "approve" | "reject", comment: string): Promise<void> {
    const res = await fetch(`/remediation-api/api/v1/remediations/${encodeURIComponent(id)}/${verb}`, {
      method: "POST", headers: { "Content-Type": "application/json", ...CSRF }, credentials: "same-origin", body: JSON.stringify({ comment }),
    });
    unauthorized(res);
    if (res.status === 403) throw new Error("Your role cannot decide remediations (operator required).");
    if (!res.ok) {
      const body = await res.json().catch(() => ({}));
      throw new Error(body.detail ?? `${res.status} ${res.statusText}`);
    }
  },
  serviceHealth: () => get<ServiceHealth[]>(`/ops-api/api/v1/services/health`),
  latestMetrics: () => get<LatestSample[]>(`/ops-api/api/v1/metrics/latest`),
  stock: () => get<StockItem[]>(`/inventory-api/api/v1/inventory`),
  order: (id: string) => get<OrderView>(`/orders-api/api/v1/orders/${encodeURIComponent(id)}`),
  async placeOrder(sku: string, quantity: number, unitPrice: number): Promise<OrderView> {
    const res = await fetch(`/orders-api/api/v1/orders`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Correlation-ID": `ui-${Date.now()}`, ...CSRF }, credentials: "same-origin",
      body: JSON.stringify({ customerId: "CUST-DASHBOARD", currency: "USD", items: [{ sku, quantity, unitPrice }] }),
    });
    unauthorized(res);
    if (res.status === 403) throw new Error("Your role cannot place orders (operator required).");
    if (!res.ok) throw new Error(`Order rejected: ${res.status}`);
    return res.json();
  },
};
