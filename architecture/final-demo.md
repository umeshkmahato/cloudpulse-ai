# Final demo script (about 15 minutes)

Everything runs locally on kind at no cost. Telemetry is SYNTHETIC and labelled as such throughout; say so out loud.
Prerequisites: Colima running (6 CPU / 10 GB), `scripts/k8s-up.sh` completed. Logins are in `.run/identity-credentials.txt` (git-ignored).

| # | Minutes | Show | Command / action | Message |
|---|---|---|---|---|
| 0 | 1 | Architecture | open `architecture/diagrams/system-overview.md` | event-driven, outbox, gateway auth, restricted controller |
| 1 | 1 | Cluster up | `kubectl -n cloudpulse get pods -o wide` | 2 replicas spread over two zones, non-root, read-only |
| 2 | 1 | Dashboard | `kubectl -n cloudpulse port-forward svc/dashboard 8088:8080`, log in as `operator` | roles: viewer < operator < admin |
| 3 | 2 | Normal traffic | `scripts/simulate-telemetry.py --scenario normal --backfill-minutes 30` | baseline for the detector |
| 4 | 3 | Overload and prediction | `scripts/simulate-telemetry.py --scenario spike --duration 120 --interval 5` | z-score + EWMA detect it; forecast is marked LOW confidence |
| 5 | 2 | AI assistant | ask "why is order-service degraded?" | answer cites evidence; validator rejects invented numbers; assistant cannot act |
| 6 | 2 | Self-healing | approve the proposed scale-out as operator | HPA floor raised, audit shows WHO, floor restored after recovery |
| 7 | 1 | Security | try the same approval as `viewer` or without a cookie | 403 / 401, attempts audited |
| 8 | 2 | Resilience | `./testing/dr/dr-drill.sh db-crash` (about 1 min) or show `architecture/dr-test-results.md` | measured, local, 0 acknowledged orders lost |
| 9 | 1 | Delivery | `scripts/ci-local.sh fast`, show `.github/workflows` | keyless auth, gated cloud jobs, plan-then-apply |

Longer drills if asked: `./testing/dr/dr-drill.sh zone-failure` (about 5 min), `./testing/dr/pitr-drill.sh`, `scripts/k8s-hpa-demo.sh`.
Regression proof: `scripts/k8s-verify.sh` (24), `scripts/k8s-security-verify.sh` (45), `scripts/k8s-remediation-e2e.sh` (26).

## If something goes wrong
- Pods Pending after a restart: `kubectl -n cloudpulse get events --sort-by=.lastTimestamp | tail`; zone drills can leave a node paused: `docker unpause cloudpulse-worker2`.
- Cooldown blocks a second remediation: it is 10 minutes by design; use the e2e script which waits it out.

## What not to claim
Real GCP behaviour, production SLAs, regional failover, real ML, or real telemetry. See the unverified list in the interview prep.
