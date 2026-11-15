# Interview Q&A in STAR format (Architect / IC3, 11+ years)

How to use this
- **CloudPulse answers** are grounded in what was actually built and measured. Say "in my POC" and quote the numbers. Everything was measured on a local kind cluster, never on GCP.
- **[YOUR STORY] answers** cover skills the POC does not prove (large migrations, legacy modernisation, Compute Engine, Cloud Functions, Redis, DNS, org-scale programs). I do not know your career, so I gave you the structure and prompts. Fill them with real projects. Do not present POC work as production experience.
- STAR = Situation, Task, Action, Result. Aim for about 2 minutes. Spend most of the time on Action and on trade-offs, which is what separates architect-level answers.
- Say the unverified items early (real GCP, BigQuery and Vertex adapters, GKE overlay, regional failover, GitHub runs). See the list at the end.

Skill coverage map
| JD requirement | Section | Evidence level |
|---|---|---|
| GKE, Kubernetes | 1, 4 | POC measured on kind; GKE overlay validated, not applied |
| Event-driven, Pub/Sub, microservices, Spring Boot | 2, 3 | POC built and tested |
| Terraform | 5 | 16 modules validated offline, never applied |
| HA and DR | 6 | POC measured locally; regional DR design only |
| Security, IAM, encryption, KMS | 7 | POC built (identity, gateway, RBAC); GCP IAM/KMS authored only |
| CI/CD (GitHub, Jenkins, Cloud Build) | 8 | Workflows authored, not run; Jenkins/Cloud Build = your story |
| Observability, AI-assisted analytics, auto-remediation | 9, 10 | POC built |
| Cost and performance | 11 | POC design plus cost gates |
| Large migrations, legacy, stateful, Redis, DNS, LB, Compute Engine, Functions | 12 | Your story |
| Leadership, communication, conflict | 13 | Your story with prompts |

---

## 1. Platform and architecture

### Q1. Walk me through a cloud-native platform you designed end to end.
- **S:** I wanted one portfolio-grade system that exercises the whole operations lifecycle: telemetry in, detection, explanation, controlled action, recovery, and delivery. Constraint: no cloud spend until validated.
- **T:** Design and build CloudPulse AI: Java 21 / Spring Boot services, React dashboard, Pub/Sub, BigQuery, Terraform, Kubernetes, with security, DR and CI/CD, in phases, each proven before the next.
- **A:** Seven Spring Boot services (orders, inventory, operations, analytics sink, AI assistant, remediation controller, identity) behind an nginx gateway that acts as a policy decision point. Services communicate through Pub/Sub topics with dead-letter queues, using a transactional outbox and idempotent consumers. I went local-first (Docker Compose, then a 3-node kind cluster) with Terraform for GCP authored and validated offline behind default-off cost gates. I wrote ADRs for the key decisions.
- **R:** A working system with 24 + 45 + 26 automated cluster checks, unit tests in every module, and Trivy clean at HIGH/CRITICAL. Honest limit: GCP was never provisioned, so cloud adapters remain unverified.

### Q2. Why local-first instead of building on GCP immediately?
- **S:** Billable GKE, Cloud SQL and NAT can run up cost before the design is proven.
- **T:** Prove behaviour and failure modes without spend, but keep the path to GCP credible.
- **A:** ADR-0001 sets cost guardrails: every billable resource sits behind a default-off Terraform variable, with an estimate and an approved reviewed plan required before apply. Behaviour is proven on kind with a Pub/Sub emulator; Terraform is validated and scanned offline.
- **R:** Zero spend, and I found real defects early (HPA fighting a direct scale, quota counting stuck pods) that would have cost debugging time on GKE. Trade-off: emulators differ from the real service, so I list those adapters as unverified rather than claiming parity.

---

## 2. Event-driven architecture

### Q3. How do you guarantee no lost or duplicated business events?
- **S:** An order must be stored and an event published; a crash between the two loses or duplicates data (the dual-write problem).
- **T:** At-least-once delivery with effectively-once processing.
- **A:** Transactional outbox: the order row and the outbox row commit in one database transaction; a relay publishes to Pub/Sub. Consumers dedupe on `eventId`. JSON Schemas plus contract tests guard payloads. Each topic has a DLQ, retries with backoff 5 to 300 s and a maximum of 5 delivery attempts, and a replay script for dead letters.
- **R:** In a 60 s broker outage drill, 0 requests failed, 138 orders were acknowledged, 0 lost, and the backlog (30 events at peak) drained 1 s after the broker returned. Trade-off: extra table, relay latency, and ordering only per key.

### Q4. Pub/Sub versus Kafka, and when would you pick each?
- **S/T:** Choosing the messaging backbone for a GCP-first platform.
- **A:** I chose Pub/Sub for managed scaling, IAM per topic and zero broker operations. I designed so business logic does not depend on broker features beyond at-least-once and ordering keys.
- **R:** Portable design. I would pick Kafka for log replay at scale, exactly-once stream processing or when the team already runs it. [YOUR STORY: a real selection decision and its cost or latency numbers.]

---

## 3. Microservices with Java / Spring Boot

### Q5. How do you keep microservices from becoming a distributed monolith?
- **S:** Seven services sharing one operational goal can couple through the database and synchronous calls.
- **T:** Independent deployability and failure isolation.
- **A:** Each service owns its schema; communication is events with versioned JSON Schemas and contract tests; synchronous calls are limited and timeout-bound; NetworkPolicies are default-deny so undeclared dependencies fail in testing. Resource limits, readiness probes and a Hikari pool of 5 per pod prevented one service starving the database.
- **R:** I hit "too many clients" in Postgres (pool 10 times pods). Fixed with pool sizing and `max_connections=200`, and the sizing is now documented. Lesson I quote: connection budget is a cluster-level resource, not per service.

### Q6. Tell me about a hard Spring Boot or dependency problem you resolved.
- **S:** Trivy flagged HIGH/CRITICAL CVEs across Spring, Jackson, Tomcat and others.
- **T:** Reach a clean scan without breaking services.
- **A:** Upgraded Spring Boot 3.3.5 to 3.5.14, added targeted version overrides, rebuilt images, re-ran all suites. Two items I could not fix (Spring Framework 7-only fixes; two Alpine libraries awaiting a base image) are in `.trivyignore` with written justification.
- **R:** Clean at CRITICAL/HIGH with documented exceptions, all tests still green. I never silently suppress findings.

---

## 4. GKE and Kubernetes

### Q7. How did you design for availability inside the cluster?
- **S:** A node or zone disappearing must not stop order placement.
- **T:** Recover within the 5 minute experimental RTO.
- **A:** Two or more replicas on the request path, PodDisruptionBudgets, HPAs, strict topology spread (`DoNotSchedule`, `nodeTaintsPolicy: Honor`, `matchLabelKeys: pod-template-hash`), and `tolerationSeconds: 30` so pods leave a dead node quickly. Default-deny NetworkPolicy, non-root read-only containers, Pod Security Admission.
- **R:** Zone-loss drill: node noticed at 47 s, write outage 50 s, all replicas Ready at 93 s, 0 acknowledged orders lost. Findings I fixed: replicas became lopsided after rolling updates, and the ResourceQuota counted pods stuck Terminating on the dead node. Caveat: a paused Docker node is not a real GCP zone outage.

### Q8. Tell me about a time automation fought your own design.
- **S:** The remediation controller scaled order-service by patching `deployments/scale`.
- **T:** Make scaling stick without two controllers fighting.
- **A:** On the live cluster I saw the HPA revert the change in about a minute. I changed the lever to the HPA's `minReplicas` (a "capacity floor"), restore it after recovery, and limited RBAC to patching three named HPAs.
- **R:** Remediation e2e (26 checks) passes, no autoscaler fight, and the blast radius is bounded by the HPA maximum. Principle: one owner per field.

---

## 5. Terraform

### Q9. How do you structure Terraform for multiple environments safely?
- **S:** One repo, dev/staging/prod, billable resources, and a team that can make mistakes.
- **T:** Reusable, reviewable, cost-safe IaC.
- **A:** 16 modules (VPC, subnets, NAT, GKE, Cloud SQL, Pub/Sub, BigQuery, IAM, KMS, Secret Manager, logging, monitoring, budget and more) composed by thin environment roots plus a bootstrap root for state. Remote state in GCS, expensive resources behind boolean gates defaulting to off, `tf-plan.sh` summarising billable additions, and a rule that apply uses a saved reviewed plan.
- **R:** `terraform validate` and Trivy config scans pass for all environments. **Never applied**; I say so. I also added a gated cross-region Cloud SQL replica variable to support the DR design.

### Q10. How do you prevent drift and risky changes?
- **A:** Plan on every PR with a read-only identity, saved plan applied only in a protected environment (authored in `terraform.yml`), state locking, least-privilege applier identity, scheduled drift plans as the next step. [YOUR STORY: a real drift or bad-apply incident and what you changed afterwards.]

---

## 6. High availability and disaster recovery

### Q11. How did you design and test DR, and what are your RTO and RPO?
- **S:** Brief target: RTO 5 min and RPO 1 min, as experimental targets, not commitments.
- **T:** Show recovery with evidence, not slides.
- **A:** Built a drill harness: a probe writes an order and reads incidents every second through the gateway and measures outage seconds, failed requests, worst latency and acknowledged-versus-lost orders. Four scenarios: zone loss, database crash, broker outage, and volume loss with point-in-time recovery from a base backup plus WAL archive (every 30 s). Wrote a DR runbook.
- **R:** DB crash: 0 s write outage, replacement Ready in 2 s. Zone loss: 50 s write outage, 93 s to full capacity. Restore: 3 to 4 s, worst data-loss window 46 s against a 60 s RPO. All met targets, but the 46 s margin is thin: I would add a streaming replica or shorter archive timeout.
- **Honest limits:** same-cluster backup volume; regional failover, replica promotion and global LB failover are design-only. [YOUR STORY: a real DR test or an incident where DR was invoked.]

### Q12. What is the difference between HA and DR, and how do you explain that to executives?
- **A:** HA keeps the service up through common failures (pod, node, zone) with no human action; DR restores service after losing a whole site or data, with defined RTO and RPO and a human decision. I show two numbers per scenario (time users were affected, data lost) and a cost per extra nine, so the business picks the target.

---

## 7. Security, IAM, encryption

### Q13. How did you secure the platform?
- **S:** The remediation controller can change the cluster, and operators approve its actions.
- **T:** Verified identity, least privilege, and an audit trail.
- **A:** identity-service issues RS256 JWTs in an HttpOnly SameSite=Strict cookie with roles VIEWER/OPERATOR/ADMIN. The nginx gateway enforces default-deny per method and path and rejects path tricks, plus a CSRF header and rate limits. The controller verifies the JWT again (algorithm pinned, issuer, audience, expiry), so a tampered token, HS256 confusion and `alg:none` fail. Append-only audit tables enforced by DB triggers. Admission policy blocks `:latest`, privileged pods and images outside the registry allow-list. Trivy for dependencies, IaC, secrets and images.
- **R:** 45 security checks pass in-cluster. I documented a STRIDE model and accepted risks (no token revocation, plaintext in-cluster traffic, no Binary Authorization yet).

### Q14. Explain your IAM and key-management approach on GCP.
- **A:** One service account per workload with least-privilege roles, Workload Identity for pods, GitHub OIDC via Workload Identity Federation (no keys), Secret Manager, and optional CMEK through a `kms` module with rotation. Cloud SQL private IP with TLS enforced. All of it is Terraform-authored and **unverified in GCP**. [YOUR STORY: a real IAM clean-up, org policy rollout or key-rotation program.]

---

## 8. CI/CD and DevOps

### Q15. Describe your delivery pipeline and how you roll back.
- **S:** Need repeatable, safe releases and no long-lived cloud credentials.
- **T:** Pipeline gates, signed immutable artifacts, fast rollback.
- **A:** GitHub Actions: CI (tests, Terraform validate, kustomize render, Trivy, image scan, kind e2e), release (SHA-tagged images, scan before push, keyless cosign), deploy (environment approval, signature verification, auto-undo on failed rollout), rollback and Terraform plan/apply workflows. Every cloud job is gated by `GCP_ENABLED`. `scripts/ci-local.sh` runs the same gates locally.
- **R:** Local gates 20 of 20; rollback rehearsal on kind: a bad image stalled the rollout while both old replicas stayed Ready, and `rollout undo` restored service in about 46 s. **The workflows have never run on GitHub.** Gaps I list: actions pinned by version not SHA, no canary. [YOUR STORY: Jenkins or Cloud Build pipelines you actually ran, with deploy frequency and failure rate.]

---

## 9. Observability and AI-assisted analytics

### Q16. How does your anomaly detection avoid alert noise?
- **S:** Threshold alerts fire constantly on noisy metrics.
- **T:** Early signal with few false alarms.
- **A:** z-score plus EWMA with a significance gate before raising an incident, deduplicated incidents, and a linear saturation forecast always labelled LOW confidence. Telemetry in the POC is synthetic and labelled as such.
- **R:** The spike scenario (CPU 40 to 90 percent, latency 300 to 2500 ms) produces a detected incident and an `alert.raised` event. Limit: tuned on synthetic data only, so false-positive rates on real traffic are unknown.

### Q17. How do you make an AI assistant trustworthy for incident response?
- **A:** It is read-only with no route to the controller. Answers are rule-based and evidence-grounded, a validator rejects numbers, citations or commands not present in the evidence, and the Vertex AI adapter is optional and unverified. The AI explains; a deterministic controller acts. Quote this principle: untrusted, non-deterministic output must not hold credentials.

---

## 10. Automated remediation

### Q18. How do you make auto-remediation safe?
- **S/T:** Automation can amplify outages; the brief required controlled self-healing.
- **A:** One executable action (raise an HPA floor) on an allow-list with bounds (for example order-service 2 to 6, step at most 2), cooldown, hourly rate limit, kill switch, human approval by default, re-checks on fresh state at execution, and measured recovery. It refuses to scale when database connections are saturated, because more pods would worsen it. Injection strings like `../x` are denied before any API call.
- **R:** 26 end-to-end checks, including 401 without a token, 403 for a viewer, idempotent second approval (409), append-only audit with WHO approved, and floor restored after recovery. Honest note: recovery is real in Kubernetes but the telemetry normalising is synthetic.

---

## 11. Cost and performance

### Q19. How do you control cloud cost in an architecture?
- **A:** Guardrails by design: default-off billable modules, a budget module with alerts, `architecture/cost-estimate.md` before any plan, billable-resource flags in plan summaries, HPA maximums and a remediation cap so automation cannot run away, and FinOps strategy (rightsizing, committed use, storage lifecycle, BigQuery partitioning). I never state pricing I have not verified. [YOUR STORY: a real saving with before and after dollars and how you measured it.]

---

## 12. Experience-based questions (fill with your real stories)

The POC does not prove these. Use this template and keep each to a real project.

| Topic | Question you will get | What to include in your STAR |
|---|---|---|
| Large migration | "Lead a migration of N workloads to GCP." | Discovery method, 6 Rs choice per workload, landing zone, wave plan, cutover and rollback, scale, timeline, who you led |
| Legacy modernisation | "Strangler pattern or rewrite?" | Seams chosen, anti-corruption layer, parallel run, data sync, how you proved parity |
| Stateful migration | "Move a database or stateful app with minimal downtime." | Tool (DMS, replication, logical dump), cutover window, rollback plan, validation checks, measured downtime |
| Networking and LB | "Design traffic management." | VPC and shared VPC, private access, Cloud NAT, global versus regional LB, health checks, Cloud Armor, DNS (Cloud DNS, private zones, failover records) |
| DNS service discovery | "How do services find each other?" | Kubernetes DNS, headless services, private Cloud DNS, TTL choices, failover routing; in the POC only in-cluster DNS (and an nginx caveat: I addressed backends by FQDN) |
| Redis/Valkey | "Where would you cache?" | Cache-aside, TTL and invalidation, stampede control, Memorystore HA tier; POC has no cache, so say what you would add (idempotency keys, rate limits, session or hot read models) |
| Compute Engine and Functions | "When VM or function over GKE?" | Licensing or lift-and-shift for VMs; event glue and low-traffic for Cloud Functions; reason and cost |
| BigQuery and real-time ingestion | "Design streaming analytics." | Pub/Sub to Dataflow or sink to BigQuery, partitioning and clustering, cost per TB, late data; POC sink has a BigQuery adapter that is unverified |
| Certifications | "Which GCP certifications?" | State only the ones you hold |

Example skeleton:
- **S:** Company, system, why change was needed (age, cost, risk, scale numbers).
- **T:** Your accountability as architect, constraints (downtime, budget, compliance, deadline).
- **A:** Options you compared (and why you rejected them), the design, risks you mitigated, how you aligned teams, what you personally built or decided.
- **R:** Numbers (downtime minutes, cost change, latency, incidents), what went wrong, what you would do differently.

---

## 13. Leadership and collaboration (IC3 expectations)

### Q20. Tell me about influencing a decision without authority.
- **Prompts:** The decision, who disagreed and why, the evidence you produced (a spike, a cost model, a drill), the ADR you wrote, the outcome. POC parallel: I used ADRs and measured drills to turn opinions about DR and security into numbers.

### Q21. Tell me about a design you got wrong.
- **POC story you can use:** My first remediation design patched `scale` and the HPA reverted it. I found it by running the failure on the live cluster, changed the lever, and documented why. Pair it with a real-career example of a larger mistake.

### Q22. How do you mentor and raise the bar?
- **Prompts:** Design reviews, templates (ADR, runbook), paved-road scripts, blameless post-incident reviews, measurable result (fewer incidents, faster onboarding).

---

## 14. Rapid-fire answers (30 seconds each)
- **CAP/consistency here?** Orders are strongly consistent in one Postgres primary; events are eventually consistent via outbox.
- **Why not service mesh?** Cost and complexity outweigh the POC benefit; in-cluster plaintext is accepted risk R4, with mTLS as the production step.
- **What breaks at 10M users?** Identity on the request path, single write primary, one cluster. Next: managed identity with short tokens, sharded or horizontally scalable store, multi-region, progressive delivery.
- **How do you test failure?** Drills with a probe and measured outage, lost-order checks, and findings fed back into manifests.
- **Biggest risk right now?** Real GCP behaviour is unproven; plan is cloud validation with the personal account, then a costed plan.

## Unverified list (state it before you are asked)
Real GCP (Terraform never planned or applied), BigQuery and Vertex AI adapters, GKE overlay, regional failover and global load balancer, replica promotion, GitHub Actions runs, cosign verification, Binary Authorization, environment approvals, real-traffic anomaly accuracy.

## Related project docs
`architecture/interview-prep.md`, `architecture/final-demo.md`, `architecture/dr-test-results.md`, `architecture/security-design.md`, `architecture/cicd.md`.
