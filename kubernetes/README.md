# Kubernetes (Phase 11)

Kustomize layout. **Only the `local` overlay has been applied (to a free local kind cluster). The `gke` overlay is authored and
server-side-validated but never applied: GKE is billable and needs explicit approval.**

```
kubernetes/
  kind-cluster.yaml          3 nodes, two fake zones (zone-a, zone-b)
  addons/metrics-server.yaml local only (GKE has its own)
  base/                      namespace+quota, RBAC, NetworkPolicies, 6 workloads (+HPA, PDB)
  overlays/local/            in-cluster Postgres + Pub/Sub emulator + their network rules; kind-friendly replicas
  overlays/gke/              Artifact Registry images, Workload Identity annotations, Cloud SQL/Google API egress
```

## Run locally
```bash
scripts/k8s-up.sh                    # builds 8 images, creates the kind cluster, deploys, waits
scripts/k8s-verify.sh                # 24 checks (hardening, HPA metrics, PDB, zones, NetworkPolicy, RBAC, business flow, pod kill, node drain)
scripts/k8s-hpa-demo.sh              # generates load; observed order-service scaling 2 -> 4 -> 6 (the HPA maximum)
kubectl -n cloudpulse port-forward svc/dashboard 8088:8080     # http://localhost:8088
scripts/k8s-down.sh
```
Needs a Docker runtime (Colima: 6 CPU / 10 GB / 50 GB was used) and `kind`. Further suites: `scripts/k8s-security-verify.sh` (45), `scripts/k8s-remediation-e2e.sh` (26), `testing/dr/dr-drill.sh`. Full guide: `doc/RUN-AND-VALIDATE.md`.

## What each control does
| Requirement | Implementation | Why |
|---|---|---|
| Probes | startup (cold JVM up to 3 min), liveness `/actuator/health/liveness`, readiness `/actuator/health/readiness` | Slow starts are not killed; unready pods get no traffic |
| Resources | requests and limits on every container, namespace ResourceQuota + LimitRange | Scheduler bin-packing, noisy-neighbour control, HPA math is relative to requests |
| HPA | order 2-6, inventory 1-4 (2-4 in gke), operations 1-3; CPU 70%; instant scale-up, 5 min scale-down window | React to load fast, avoid flapping |
| PDB | `minAvailable: 1` for order and inventory | Node drains/upgrades never take every replica |
| Zones | topologySpreadConstraints on zone and host (ScheduleAnyway) | Survive a zone/node loss without blocking scheduling |
| Rollouts | RollingUpdate `maxUnavailable: 0`, `preStop sleep 5`, 40 s grace | Zero-downtime deploys, connections drain |
| Pod security | namespace PSA `restricted` (gke) / `baseline` (local, because Postgres/emulator images are not hardened); non-root, read-only rootfs, drop ALL caps, seccomp RuntimeDefault | Limits blast radius of a compromised container |
| NetworkPolicy | default-deny both directions, DNS allowed, explicit per-flow allows (dashboard -> services, assistant -> operations, apps -> DB/Pub/Sub) | Lateral movement blocked; verified by a probe pod |
| RBAC | app SAs have no token and no permissions; `remediation-controller` may only patch `minReplicas` of three named HPAs (plus read deployments/HPAs) | The Phase 12 controller can raise a capacity floor, nothing else: no direct scaling, secrets or exec |
| Config / secrets | ConfigMap for non-secret config; DB password is a Secret created at deploy time (never committed); GKE: Secret Manager + Workload Identity | No credentials in git |
| Gateway | nginx-unprivileged serves the SPA and reverse-proxies `/ops-api`, `/orders-api`, `/inventory-api`, `/assistant-api` (same-origin, security headers, lazy DNS) | Replaces the Vite dev proxy; no CORS |

## Security layer (Phase 13)
- `identity-service` + nginx `auth_request`: every API call is authenticated and authorised at the gateway (default deny); see `architecture/security-design.md`.
- `base/admission-policies.yaml`: ValidatingAdmissionPolicy (resource limits, no `:latest`, no privileged/host access, image-registry allow-list per overlay).
- Secrets are created by `scripts/k8s-up.sh` (identity signing key, bcrypt user hashes, DB password) and never committed.

## GKE specifics (not applied)
- Workload Identity: KSA annotation `iam.gke.io/gcp-service-account` per service (SAs from Terraform `iam` module); the Google SA also needs
  `roles/iam.workloadIdentityUser` for `serviceAccount:cloudpulse-ai-poc.svc.id.goog[cloudpulse/<ksa>]` (set `k8s_workload_identity` in Terraform after the cluster exists).
- Private Cloud SQL: replace `CLOUD_SQL_PRIVATE_IP`; narrow the `10.0.0.0/8` egress to the reserved range.
- External ingress/load balancer is deliberately **not** included (`overlays/gke/OPTIONAL-ingress.yaml.disabled`): it bills while it exists.
- Cost reminder: see `architecture/cost-estimate.md`; create the cluster only after an approved plan and tear it down after the demo.

## Interview notes
- **Why Kustomize over Helm?** Plain YAML that diffs cleanly, overlays for env differences, no templating language; Helm wins when distributing a chart to others.
- **Why Autopilot (Terraform module)?** No node pools to patch or size, per-pod billing, hardened defaults; Standard when you need DaemonSets, privileged workloads or node-level tuning.
- **Scaling limits:** HPA on CPU only (no custom metrics yet; add request-rate/queue-lag via Managed Prometheus + KEDA for the consumers); DB connections multiply with replicas (the detector's advice: check the pool before scaling).
- **Failure scenarios verified locally:** pod kill (traffic continues), node drain respecting the PDB, default-deny blocks an intruder pod.
- **Security risks left:** no admission policy (Gatekeeper/Kyverno) or image signing/Binary Authorization yet (Phase 13/15); local overlay relaxes PSA to baseline.
- **10M users:** regional clusters, multi-cluster ingress, KEDA for event-driven consumers, node auto-provisioning, read replicas and a connection pooler (PgBouncer/AlloyDB), per-service namespaces and quotas.

## Local-environment notes (verified the hard way)
- `registry.k8s.io` fails TLS behind a corporate proxy CA, so the local metrics-server uses the Rancher mirror on Docker Hub. Real GKE ships metrics-server.
- `kind load` of third-party multi-platform images fails with Docker 29: only our own images are side-loaded, the nodes pull the rest.
- The Pub/Sub emulator is in-memory: if its pod is rescheduled the topics vanish and consumers must be restarted. It is pinned to zone-a with Postgres so drain drills use zone-b.
- nginx does not merge `add_header` across contexts and ignores the pod's DNS search domains: both were real bugs found by `k8s-verify.sh` (security headers via an included file; backends addressed by FQDN).
- Scale-in after load stops takes the 5-minute stabilisation window (by design).
- **Connection budget (found by the e2e):** the default Hikari pool (10) times 9+ JVM pods exhausted Postgres' 100 connections ("too many clients"), breaking unrelated checks. Pools are now 5 and local Postgres allows 200. HPA scale-out multiplies connections: size `sum(max replicas x pool)` against Cloud SQL `max_connections` (database flag) or add a pooler (PgBouncer). The detector's DB-saturation guardrail exists for exactly this.
