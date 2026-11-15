# How to run CloudPulse AI and what to validate

Everything runs locally and costs nothing. No GCP account is needed. Telemetry is SYNTHETIC (labelled as such), never real GCP data.
Nothing in this repo has been applied to GCP (see "Not validated" at the end).

## 1. Prerequisites (macOS, Apple silicon assumed)
| Tool | Why | Install |
|---|---|---|
| Container runtime with a Docker API | builds images, hosts kind | Colima (`brew install colima`) or Docker Desktop. Colima size used: `colima start --cpu 6 --memory 10 --disk 50` |
| docker CLI, kubectl | talk to the runtime and cluster | usually present with Docker Desktop; else `brew install docker kubectl` |
| kind | local Kubernetes | `brew install kind` |
| JDK 21, Maven 3.9 | build the 7 Java modules | `brew install openjdk@21 maven` |
| Node 20 | dashboard | `brew install node@20` |
| Python 3 | telemetry simulator, DR report | preinstalled |
| Terraform 1.9+, Trivy | IaC validation, security scans (optional) | `brew install hashicorp/tap/terraform trivy` |

Behind a corporate TLS proxy: `registry.k8s.io` pulls and `apk upgrade` can fail, and `kind load` fails for third-party multi-platform images. The repo already works around this (own images only are side-loaded; metrics-server uses a mirrored image).

## 2. Path A: full platform on Kubernetes (kind). Recommended demo path
```bash
scripts/k8s-up.sh                      # builds 8 images, creates 3-node kind cluster, secrets, deploys, waits (several minutes)
kubectl -n cloudpulse get pods -o wide # all Running; 2 replicas of order/inventory/identity/dashboard
kubectl -n cloudpulse port-forward svc/dashboard 8088:8080     # open http://localhost:8088
cat .run/identity-credentials.txt      # generated demo logins: viewer / operator / admin (git-ignored)
```
Log in as `operator` (place orders, approve remediations) or `admin` (kill switch, telemetry injection).

Generate data: `python3 scripts/simulate-telemetry.py --scenario normal --backfill-minutes 30`, then
`python3 scripts/simulate-telemetry.py --scenario spike --duration 120 --interval 5` (see its `--help` for credentials/URL options).

Stop and delete: `scripts/k8s-down.sh` (deletes the cluster and its data).

## 3. Path B: lightweight local run (Docker Compose + jars)
```bash
cp .env.example .env                   # already present locally; edit only for local values, never commit secrets
docker compose up -d                   # postgres, valkey, pub/sub emulator, prometheus, grafana
for m in services/order-service services/inventory-service services/operations-service services/ai-incident-assistant data-pipeline/analytics-sink; do (cd $m && mvn -q -DskipTests package); done
scripts/run-local.sh start             # starts the Java services; `status` / `stop` also available
scripts/smoke-local.sh                 # infra + Pub/Sub round trip
docker compose down -v                 # stop and remove data
```
Note: the identity gateway, remediation controller and admission policy only exist in Path A.

## 4. Validation checklist
Run each suite and compare with the expected result.

| What | Command | Expected | Time |
|---|---|---|---|
| Unit tests, one module | `cd services/order-service && mvn -q verify` (repeat per module) | BUILD SUCCESS | 1-2 min each |
| Dashboard | `cd frontend/operations-dashboard && npx tsc -b --noEmit && npx vitest run` | 23 tests pass | <1 min |
| All non-cloud CI gates | `scripts/ci-local.sh` (`fast` skips Trivy) | `20 passed, 0 failed` | 10-15 min |
| Kubernetes platform | `scripts/k8s-verify.sh` | 24 pass | ~5 min |
| Security controls | `scripts/k8s-security-verify.sh` | 45 pass (401/403 cases, tampered tokens, admission deny, NetworkPolicy) | ~5 min |
| Self-healing | `scripts/k8s-remediation-e2e.sh` | 26 pass (approval, HPA floor raised, audit, floor restored) | ~15 min (waits out a cooldown) |
| Event flow (Compose path) | `scripts/e2e-phase5.sh`, `e2e-phase6.sh`, `e2e-phase7.sh`, `e2e-phase9.sh`, `e2e-phase10.sh` | all PASS | minutes each |
| Autoscaling | `scripts/k8s-hpa-demo.sh` | order-service scales 2 to 4 and back (6 max) | ~4 min |
| Supply chain | `scripts/security-scan.sh` | clean at CRITICAL/HIGH (two documented exceptions in `.trivyignore`) | minutes |
| DR drills | `testing/dr/dr-drill.sh db-crash` / `zone-failure` / `broker-outage` / `all`, `testing/dr/pitr-drill.sh` | RTO 5 min and RPO 1 min met; 0 acknowledged orders lost; then `python3 testing/dr/report.py` | 1-6 min each |
| Rollback rehearsal | steps in `architecture/runbooks/rollback-runbook.md` | rollout stalls without losing capacity; undo restores it | 1 min |
| Terraform | `terraform -chdir=infrastructure/terraform/environments/dev init -backend=false && terraform -chdir=infrastructure/terraform/environments/dev validate` (also staging, prod) | Success | seconds |

Manual checks in the dashboard: incidents appear after the spike; the AI assistant answers cite evidence; approving a remediation as `viewer` is refused, as `operator` works and the audit shows who approved; DR page shows measured results labelled as local and experimental targets.

Note: DR drills pause a kind node and force-delete Postgres. Run them only on the local cluster. After a zone drill, if a node is stuck: `docker unpause cloudpulse-worker2`.

## 5. Not validated (state this in any demo)
Real GCP (Terraform has never been planned or applied; no GCP resources exist), BigQuery and Vertex AI adapters, the `gke` overlay, regional failover and global load balancing, GitHub Actions runs (the repo has no remote), cosign verification, Binary Authorization. Cost so far: $0. Before any cloud step: personal Google account only (not the corporate one), cost estimate in `architecture/cost-estimate.md`, reviewed plan, explicit approval.

## 6. Where to read next
`README.md`, `architecture/HLD.md`, `architecture/final-demo.md` (15-minute script), `architecture/interview-prep.md`, `doc/interview-star-qa.md`, `architecture/dr-test-results.md`, `architecture/security-design.md`, `architecture/cicd.md`.
