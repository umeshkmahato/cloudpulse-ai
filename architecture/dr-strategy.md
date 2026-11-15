# High availability and disaster recovery strategy (Phase 14)

Targets (experimental, not commitments): **RTO 5 minutes, RPO 1 minute.** Everything below is marked **measured** (local kind
cluster, see `dr-test-results.md`), **authored** (Terraform/manifests validated but not applied) or **design only**.

## Failure domains and how each is covered
| Failure domain | Mechanism | Status |
|---|---|---|
| Process / pod | Probes, ReplicaSets, graceful shutdown, rolling updates with `maxUnavailable: 0` | measured (Phase 11-12) |
| Node | `tolerationSeconds: 30` for unreachable/not-ready (default 300 s), PDBs, 2 replicas on the order path | measured |
| Zone | Strict zone spread (`DoNotSchedule` + `nodeTaintsPolicy: Honor` + `matchLabelKeys: pod-template-hash`), stateless services, regional GKE (authored) | measured locally with 2 fake zones |
| Database instance | StatefulSet restart on a persistent volume (local); Cloud SQL `REGIONAL` HA (authored) | local measured; cloud authored |
| Data loss / corruption | Base backup + continuous WAL archive (30 s) with PITR restore; Cloud SQL backups + PITR (authored); restores rehearsed by script | measured locally |
| Messaging | Transactional outbox, at-least-once, idempotent consumers, DLQ + replay, 7-day retention | measured |
| Region | Cross-region Cloud SQL replica (authored, gated), second region via the same Terraform/Kustomize, global LB or DNS failover | **design only** |
| Operator error / bad deploy | Rollback (`rollout undo`), controller guardrails, approvals, audit | measured (Phase 12) |

## What the numbers mean
- **RTO** = from the first failed user request to the first successful one afterwards (probe through the authenticating gateway, 1 request per second).
- **RPO** = acknowledged orders missing after recovery (zero for pod/node/DB-process failures) and, for volume loss, the time between the newest row in production and the newest row recoverable from the frozen backup archive.
- Measured outage is bounded by *detection* time (about 50 s for a dead node) plus routing convergence; capacity restoration takes longer than user-visible recovery because pods must reschedule.

## Design decisions (interview-ready)
- **Active-active across zones, active-passive across regions.** Zones are cheap and low-latency; a second region doubles cost and needs data replication, so it is justified only by the RTO/RPO and regulatory needs.
- **Why the outbox gives RPO 0 for events:** the order and its event commit in one transaction; the broker can be down without losing intent.
- **Why PITR over frequent dumps:** a 1-minute RPO with dumps means a dump every minute; WAL archiving gives seconds at a fraction of the cost.
- **Backups in the same cluster are not DR.** The local PVC proves the procedure only; cloud copies must live in another region (and ideally another project with locked retention).
- **Findings that improved the design:** default 300 s node-eviction tolerance; soft zone spread leaving both replicas in one zone; rolling updates leaving replicas lopsided (fixed with `matchLabelKeys`); Hikari pool times replica count exhausting `max_connections`.

## Limits and what would change at 10M users
Single-writer Postgres is the scaling and availability ceiling: add read replicas, a pooler (PgBouncer), then shard or move the order write path to a horizontally scalable store (Spanner/AlloyDB). Pub/Sub scales on its own. Per-region cells with global routing limit blast radius; automate failover drills (game days) and measure them continuously.

## Cost of resilience (pricing unverified; see `cost-estimate.md`)
Regional GKE and Cloud SQL HA roughly double the data-tier compute cost; a cross-region replica adds another instance plus egress; a warm second region adds a full cluster. Gated in Terraform (`enable_cloud_sql`, `dr_replica_region`), never applied by default.
