# Disaster recovery runbook

Experimental POC targets: **RTO 5 min, RPO 1 min.** They are targets for rehearsal, not service-level commitments. Measured results are in
[dr-test-results.md](../dr-test-results.md). Local commands assume the kind cluster; the cloud column is the intended GCP equivalent
(authored in Terraform, **not applied or tested**).

## 0. Triage (first 2 minutes)
1. Is the failure user-visible? Dashboard "Disaster Recovery" page and `GET /ops-api/api/v1/services/health`; look at the open incidents (Predictive page).
2. What failed: one pod, one node/zone, the database, the broker, or everything?
3. Freeze automation if it could make things worse: kill switch (`POST /remediation-api/api/v1/remediation/kill-switch`, ADMIN).
4. Declare the incident, note the time (RTO clock starts at the first failed customer request).

## 1. Pod or process failure
Self-healing: ReplicaSet replaces it, readiness gates traffic, PDB protects voluntary disruption. Verify `kubectl -n cloudpulse get pods`.
Expected recovery: seconds. Nothing to do unless it crash-loops (check logs, last deploy; roll back with `kubectl rollout undo deploy/<name>`).

## 2. Node or zone failure
- Detection: node `NotReady` after about 40-50 s (kubelet heartbeat grace). Pods carry `tolerationSeconds: 30`, so eviction starts about 30 s later (default would be 300 s).
- Order path runs on 2 replicas in 2 zones with strict zone spread (`nodeTaintsPolicy: Honor`), so the surviving zone keeps serving; expect a short window of failed or slow requests until the Service stops routing to dead pods.
- Actions: confirm the surviving zone has capacity (`kubectl describe quota`, HPA); if the zone is permanently lost, replace nodes (GKE: node auto-repair or a new node pool in another zone; regional cluster control plane is unaffected).
- Cloud: regional GKE cluster (3 zones), Cloud SQL HA (`availability_type=REGIONAL`) fails over automatically in about 1-2 minutes (vendor-documented range, UNVERIFIED here).

## 3. Database failure
| Failure | Procedure | Local result |
|---|---|---|
| Pod/instance crash | StatefulSet (local) or Cloud SQL HA failover restarts it; connection pools reconnect; the outbox keeps events | measured, see results |
| Corruption or accidental delete of data | Point-in-time restore to just before the event (below), then cut over | measured |
| Volume or region loss | Restore from base backup + WAL archive (local drill) / Cloud SQL backup or cross-region replica promotion (cloud) | measured locally; cloud UNVERIFIED |

### Point-in-time restore (local rehearsal, `testing/dr/pitr-drill.sh`)
1. Take or locate a base backup in `/backup/base/<id>/` and the WAL archive in `/backup/wal/` (WAL ships at least every 30 s: `archive_timeout=30`).
2. Start a scratch Postgres on the restored files: extract `base.tar.gz`, `touch recovery.signal`, set `restore_command='cp /backup/wal/%f %p'` (add `recovery_target_time=...` for PITR), run with `archive_mode=off` so it can never write into the real archive.
3. Wait until `pg_is_in_recovery()` is false, validate row counts and the newest markers against what you expect.
4. Cut over: stop writers (scale apps to 0), swap the volume/instance (or point `DB_URL` at the restored instance), start apps, re-enable automation.
5. Replay events: unpublished outbox rows publish automatically; for consumers use the DLQ replay (`scripts/dlq-replay.sh`) or `seek` the subscription (cloud).
- Cloud: `gcloud sql backups restore` / clone to a point in time, or promote the cross-region replica (`dr_replica_region` in the `cloud-sql` module, gated, billable).
- **Always rehearse restores**: an untested backup is not a backup.

## 4. Message broker failure
- Producers are unaffected: orders are accepted and events wait in the transactional outbox (`orders.outbox_events`, gauge `outbox_events_pending`).
- Managed Pub/Sub is a regional/multi-zone service; message retention keeps undelivered messages (7 days configured). After recovery the relay publishes the backlog; consumers dedupe on `eventId`.
- Local emulator only: it is in-memory, so after a restart re-run the init job and restart consumers (`testing/dr/dr-drill.sh broker-outage` does this).
- Poison messages: DLQ + `scripts/dlq-replay.sh <topic>`. Cloud replay: `gcloud pubsub subscriptions seek <sub> --time=<ts>` (needs `retain_acked_messages` or snapshots; not enabled by default, because retained acked messages cost storage).

## 5. Regional disaster (cloud design, not built)
1. Detect: global load balancer health checks fail for the primary region, Cloud Monitoring alert.
2. Decide and announce; freeze deploys.
3. Promote the Cloud SQL cross-region replica (RPO = replication lag, usually seconds; RTO = promotion time).
4. Bring up the stack in the DR region (Terraform environment + Kustomize `gke` overlay with a different registry/cluster), scale it, point secrets and `DB_URL` at the promoted instance.
5. Traffic failover: global external HTTPS load balancer with a backend per region (health-checked failover) or Cloud DNS failover routing with low TTL.
6. Validate with synthetic orders (probe), then communicate. Failback is a planned, separate change (re-seed the old region as a replica first).
Cost note: a warm DR region means paying for a second cluster plus a replica continuously; a cold DR region (Terraform apply on demand) is cheaper but its RTO is longer. The choice is a business trade-off.

## 6. After the incident
Blameless review, update this runbook and the drill harness, re-run `testing/dr/dr-drill.sh all` and `pitr-drill.sh`, store results with the date.
