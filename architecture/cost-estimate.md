# Cost estimate and guardrails (Phase 2)

**Status of numbers:** only the figures marked *(web-checked)* come from Google Cloud pages read on 2026-11-13 via search
snippets, not the live pricing calculator. Everything else is intentionally left without a price. Re-verify with the
[Pricing Calculator](https://cloud.google.com/products/calculator) for the target region (asia-south2 availability and
prices are **unverified**) before any `terraform apply`.

## What Terraform creates by default (all `enable_*` gates false)
| Resource | Idle cost? | Notes |
|---|---|---|
| API enablement | No | Free to enable |
| Pub/Sub topics + subscriptions (+DLQs) | Usage-based | Free tier *(web-checked)*: 10 GiB messages/month per billing account; topic retention storage is billable |
| BigQuery datasets + partitioned tables | Usage-based | Free tier *(web-checked)*: 10 GiB storage, 1 TiB queries/month; streaming inserts/Storage Write API are billed separately (verify) |
| Cloud Storage buckets (3) | Usage-based | Free tier *(web-checked)* applies to **US regions only**; asia-south2 storage is billed from the first byte (verify rate) |
| Artifact Registry repo | Usage-based | 0.5 GB/month free *(web-checked)*; cleanup policies keep images bounded |
| Secret Manager secrets (2) | Usage-based | 6 secret versions + 10,000 accesses/month free *(web-checked)*; user-managed replication in 1 region |
| Service accounts, IAM, WIF pool | No | Free |
| VPC, subnet, firewall | No | Free (egress/flow logs are not) |
| Monitoring alert policies, notification channel | Low | Verify alerting/metric ingestion pricing |
| Logging sink, exclusion, `_Default` retention 30d | Usage-based | Ingestion above the free allocation is billed; exclusion reduces it (verify allocation) |
| Billing budget | No | Alerts only; **does not stop spending** |

## Gated resources (billable while they exist; each needs an approved plan)
| Gate | Resources | Why it costs | Approval needed |
|---|---|---|---|
| `enable_gke` | GKE Autopilot cluster | Cluster management fee of $0.10 per cluster-hour *(web-checked)* (about $73 for a 730 h month) plus pod CPU/memory. Free tier gives $74.40/month credit per billing account that offsets one Autopilot/zonal cluster fee *(web-checked)*, but not pod compute or regional clusters | Yes |
| `enable_cloud_sql` | Cloud SQL PostgreSQL + private services access | Instance bills 24x7 even when idle; `REGIONAL` HA roughly doubles compute; backups/PITR storage | Yes |
| `enable_cloud_nat` | Router + NAT | Hourly gateway charge + per-GB processing | Yes (needed only for private nodes pulling from the internet) |
| `enable_kms` | Key ring + key | Per-key-version monthly cost; key rings/keys cannot be deleted | Yes |
| (later) Dataflow | Not created in Phase 2 | Workers bill while jobs run; local streaming used instead | Yes |
| (later) Vertex AI | Not created | Per-token/endpoint pricing; optional | Yes |

Cloud SQL, NAT and Dataflow prices are **not stated here on purpose**: they were not verified.

## Cheapest credible cloud demo (proposal, not applied)
Enable only `enable_gke` for the live Phase 11/12 demo window (hours, not days), keep Cloud SQL off (use the in-cluster
Postgres from Compose/Kubernetes manifests), keep NAT off (Autopilot + Artifact Registry + Private Google Access need none
for Google APIs), then `terraform destroy` the cluster. Pair with the budget alerts.

## Controls in place
1. Budget with 50/90/100% actual and 100% forecast alerts (`modules/budget`), emailed via Monitoring channel. Alerts only.
2. All billable-idle resources default OFF; `scripts/tf-summary.py` flags them in every plan (exit code 3).
3. `scripts/tf-apply.sh` applies only a saved reviewed plan, requires typing the environment name, and refuses plans with
   billable/destructive changes unless `I_ACCEPT_COSTS=yes`. No `-auto-approve` anywhere.
4. Retention/expiry: BigQuery partition expiry (dev), bucket lifecycle rules, Artifact Registry cleanup, `_Default` log retention.
5. Deletion protection on data resources when `protect_resources=true` (staging/prod defaults).

## Sources (web-checked 2026-11-13, snippets only)
- [Free Google Cloud features](https://docs.cloud.google.com/free/docs/free-cloud-features)
- [Free Trial and Free Tier](https://cloud.google.com/free)
- [GKE pricing](https://cloud.google.com/kubernetes-engine/pricing)
- [Secret Manager pricing](https://cloud.google.com/secret-manager/pricing), [Storage pricing](https://cloud.google.com/storage/pricing)
