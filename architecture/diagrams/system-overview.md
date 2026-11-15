# System overview (Mermaid)

```mermaid
flowchart LR
  B[Browser] -->|HTTPS, cookie| GW[nginx gateway<br/>auth_request, default-deny]
  GW -->|verify| ID[identity-service<br/>RS256 JWT]
  GW --> ORD[order-service]
  GW --> INV[inventory-service]
  GW --> OPS[operations-service<br/>anomaly detection, twin]
  GW --> AI[ai-incident-assistant<br/>read-only, evidence-grounded]
  GW --> RC[remediation-controller<br/>allow-list, approval, audit]
  ORD -- outbox --> PS[(Pub/Sub topics + DLQs)]
  INV --> PS
  PS --> OPS
  PS --> SINK[analytics-sink] --> BQ[(BigQuery adapter - unverified)]
  OPS -- alert.raised --> PS
  RC -->|patch HPA minReplicas only| K8S[Kubernetes API]
  OPS --> PG[(Postgres / Cloud SQL)]
  ORD --> PG
  RC --> PG
```

Local reality: kind cluster, Postgres StatefulSet, in-memory Pub/Sub emulator, synthetic telemetry. GCP resources are Terraform-authored, validated offline, never applied.
