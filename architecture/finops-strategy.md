# FinOps Strategy (living document)
## Cost register - services with billing risk (status verified later; no free-tier claims made yet)
| Service | Billing risk | Phase | Gate |
|---|---|---|---|
| GKE (Autopilot/Standard) | High: cluster/node/pod charges | 11 | Explicit approval + estimate |
| Cloud SQL PostgreSQL | High: instance runs 24/7 | 6/11 | Explicit approval; local Postgres by default |
| Cloud NAT | Medium: hourly + data processing | 2 | Plan only |
| External Load Balancer | Medium-High: forwarding rule hourly | 14 | Plan only |
| Dataflow | High: worker VM hours | 7 | Local streaming first |
| Pub/Sub, BigQuery, GCS, Artifact Registry, Secret Manager, KMS, Monitoring/Logging | Usage-based; some free tiers | 6-7 | Verify current pricing page, set budget |
| Vertex AI | Per-token/usage | 10 | Optional; rule-based fallback |
