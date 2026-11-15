# Interview preparation

## 30-second pitch
CloudPulse AI is an event-driven operations platform: services publish through a transactional outbox to Pub/Sub, a detector spots anomalies and forecasts saturation, an evidence-grounded assistant explains incidents, and a deliberately small controller can scale capacity only after approval, within bounds, with a tamper-evident audit. It was built local-first on kind at zero cost, with GCP infrastructure authored in Terraform but not yet applied.

## Decisions and the trade-offs to defend
| Decision | Why | Trade-off / what I would change |
|---|---|---|
| Local-first, GCP last (ADR-0001/2) | no spend, fast feedback | cloud adapters (BigQuery, Vertex, real Pub/Sub) are unverified |
| Transactional outbox + idempotent consumers | no lost or duplicated business events | extra table and relay latency (measured catch-up 1 s after a 60 s broker outage) |
| AI recommends, controller acts | untrusted output must not hold credentials | slower than full automation |
| HPA `minReplicas` as the lever | two controllers fighting over replicas amplify outages; found on the live cluster | floor adds cost until restored |
| Gateway policy decision point + defence in depth at the controller | one auditable policy | gateway is on the request path; cache or JWKS at the edge at scale |
| Strict zone spread + short eviction tolerance | recover in about 90 s locally | `DoNotSchedule` can leave pods Pending when a zone is gone |
| WAL archive + base backup (PITR) | measured restore 3-4 s, loss window up to 46 s | window close to the 60 s RPO; add a streaming replica |
| Keyless CI, gated cloud jobs | no secrets to leak, no accidental spend | pipelines never run on GitHub yet |

## Numbers I can quote (all measured locally on kind; none are GCP or production figures)
- Zone loss: node noticed 47 s, write outage 50 s, replicas restored 93 s, 0 acknowledged orders lost.
- DB crash: replacement Ready 2 s, 0 s write outage. Broker outage 60 s: 0 failed requests, 138 orders acknowledged, 0 lost.
- Backup restore: 3-4 s, worst data-loss window 46 s.
- Tests: 24 + 45 + 26 cluster checks, unit tests in 7 services, 23 dashboard tests, Trivy clean at HIGH/CRITICAL with two documented exceptions.
- RTO 5 min / RPO 1 min are experimental targets, not commitments.

## Hard questions
- **Is the AI real?** The default path is rule-based and evidence-grounded; the Vertex adapter is optional and unverified. Say so.
- **Is the forecast reliable?** No: a linear projection marked LOW confidence. It is a prompt to look, not a decision.
- **Why not auto-remediate?** Blast radius and accountability. Auto-approve exists as an explicit policy, off everywhere, and refuses when DB connections are saturated.
- **What breaks at 10M users?** Identity on the request path, single Postgres write primary, in-cluster plaintext, one cluster. Next: managed identity with short tokens, mesh mTLS, sharding or Spanner-class store, multi-region active-passive with measured failover, SLO-driven delivery.
- **What did testing teach you?** The HPA undid a direct scale; quota counted pods stuck Terminating on a dead node; Kubernetes does not rebalance after a drill; my first capacity timer passed instantly. Each was found by running the failure, not by reading.
- **Cost?** $0 so far; every billable resource is behind a default-off variable and `architecture/cost-estimate.md`.

## Unverified list (state it before being asked)
Real GCP (Terraform never planned or applied), BigQuery and Vertex adapters, GKE overlay, regional failover and global LB, GitHub Actions runs, cosign verification, Binary Authorization, GitHub environment approvals.
