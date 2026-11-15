// ILLUSTRATIVE FIXTURES for pages whose backend is not built yet. Always rendered under a SAMPLE DATA badge.
// None of this is measured: do not quote it as real telemetry.
export const SAMPLE_DEPLOYMENTS = [
  { name: "order-service", ready: "2/2", hpa: "2-6 @ 70% CPU", cpu: "250m / 500m", mem: "384Mi / 512Mi", restarts: 0, zones: "2" },
  { name: "inventory-service", ready: "2/2", hpa: "2-4 @ 70% CPU", cpu: "250m / 500m", mem: "384Mi / 512Mi", restarts: 1, zones: "2" },
  { name: "operations-service", ready: "1/1", hpa: "1-3 @ 70% CPU", cpu: "250m / 500m", mem: "384Mi / 512Mi", restarts: 0, zones: "1" },
  { name: "analytics-sink", ready: "1/1", hpa: "-", cpu: "200m / 400m", mem: "256Mi / 384Mi", restarts: 0, zones: "1" },
];

export const SAMPLE_COST = {
  estimateMonthly: [
    { item: "GKE Autopilot cluster fee", value: 73, note: "$0.10/h x 730 h (web-checked list price)" },
    { item: "Pod compute (requests)", value: 0, note: "not estimated yet" },
    { item: "Pub/Sub", value: 0, note: "within free tier assumption (unverified for asia-south2)" },
  ],
  recommendations: [
    { title: "Right-size order-service requests", detail: "Requests 500m CPU vs p95 usage 180m (sample). Lower to 300m.", saving: "illustrative" },
    { title: "Keep Cloud SQL disabled for the demo", detail: "Instance bills 24x7 while it exists. Use in-cluster Postgres for the live demo window.", saving: "illustrative" },
    { title: "Add BigQuery partition filters", detail: "Dashboards must prune by partition to bound bytes scanned.", saving: "illustrative" },
  ],
};

export const SAMPLE_SECURITY = [
  { time: "10:02:11", severity: "high" as const, type: "iam.policy.change", detail: "Role binding added to service account (audit log)", source: "Cloud Audit Logs" },
  { time: "10:04:52", severity: "medium" as const, type: "auth.failed", detail: "5 failed API authentications from one client in 60 s", source: "API gateway" },
  { time: "10:09:30", severity: "low" as const, type: "image.vuln", detail: "Medium CVE in base image layer", source: "Artifact scan" },
  { time: "10:15:07", severity: "critical" as const, type: "netpol.violation", detail: "Denied egress from order-service to unknown host", source: "Network policy log" },
];

