# Controlled self-healing (Phase 12)

Principle: **an AI or detector may recommend; only a restricted controller with predefined actions may act, and every action is
approved (human or explicit policy), bounded, audited and measured.** Nothing in the platform can run an arbitrary command.

## Flow
```
incident (Phase 9) recommends SCALE_DEPLOYMENT
   -> PROPOSED  (allow-list + bounds + warnings evaluated and recorded)
   -> APPROVED  (authorised operator with the approver token, OR the explicit automated policy; default: human only)
   -> EXECUTING (re-checks on FRESH state: kill switch, cooldown, hourly rate limit, bounds)
   -> MONITORING (watch incident + ready pods)
   -> RECOVERED (time-to-recover measured, original capacity floor restored)  |  NOT_RECOVERED (floor stays, operator decides)
   other exits: REJECTED, EXPIRED (15 min), BLOCKED (guardrail), FAILED (API error)
```
Every transition is an audit row and a `remediation.event` on the `remediation-events` topic.

## What the controller can do (and nothing else)
| Layer | Control |
|---|---|
| Catalogue | One executable action: raise the capacity floor of an allow-listed deployment. `INSPECT_ONLY` recommendations never execute |
| Allow-list | order-service 2-6 (step <= 2), inventory-service 1-4 (<= 2), operations-service 1-3 (<= 1). Anything else, including injection attempts like `../x` or `order-service; rm -rf /`, is denied before any API call |
| Lever | The **HPA's `minReplicas`**, not `deployments/scale`: the HPA owns replica counts. A first design patched `scale` and the HPA undid it within about a minute (found on the live cluster), so the floor is raised instead and restored after recovery |
| Bounds | Never above the HPA maximum, never more than `max-step` at once, re-clamped against fresh cluster state at execution time |
| Rate | Cooldown per deployment (10 min) and max 3 actions per hour |
| Safety | Never auto-approved when DB connections are saturated (more replicas multiply connections and can worsen the incident); at maximum replicas the proposal is BLOCKED with "investigate other causes" |
| Kill switch | `POST /api/v1/remediation/kill-switch` (token): approved work waits, nothing executes |
| Dry-run | `mode=dry-run` runs the whole workflow with an in-memory scaler and never contacts Kubernetes (default outside the cluster) |
| Kubernetes RBAC | ServiceAccount `remediation-controller`: get/list deployments and HPAs, **patch only the three named HPAs**. Cannot scale deployments, read secrets, exec or touch any other object. Verified with `kubectl auth can-i` |
| Network | Only this pod may reach the API server (NetworkPolicy); it can reach only operations-service, Postgres and Pub/Sub otherwise |
| No AI path | The assistant is read-only and has no route to the controller; the controller acts on incidents from the detector, never on model output |

## Approval and identity
Approve/reject/kill-switch need `Authorization: Bearer <approver token>` from a Kubernetes Secret (compared in constant time; if no token is
configured approvals are disabled: fail closed). The operator name is recorded with every decision. This is a deliberate stop-gap:
**Phase 13 replaces it with per-user identity (OIDC / IAP) and roles**, so approvals are attributable to a verified person.

## Automated policy (explicit, off by default)
`policy.auto-approve=true` approves without a human only when: service is in the policy list, incident kind is DETECTED (not a forecast),
risk >= 80, and DB connections are not saturated. The decision records the policy name as the actor. It is disabled in every overlay.

## Audit
`remediation_audit` is append-only **in the database** (trigger rejects UPDATE/DELETE; verified), recording PROPOSED, APPROVAL_REQUIRED,
APPROVED/REJECTED/AUTO_APPROVED, EXECUTING, EXECUTED, BLOCKED, FAILED, RECOVERED, NOT_RECOVERED, CAPACITY_FLOOR_RESTORED, KILL_SWITCH_*.
Because the table lives in the application database an owner could still drop the trigger; production hardening is to stream audit rows to
Cloud Logging / a locked bucket with retention (Phase 13).

## Recovery measurement (and its honesty)
Time-to-recover = incident resolution time minus execution time. In this POC telemetry is SYNTHETIC: the Kubernetes change is real, but the
"recovery" comes from the simulator returning to normal, so the outcome text says so. Real recovery needs real load plus real capacity effects.

## Interview notes
- **Why not let the AI act?** Untrusted, non-deterministic output must not hold credentials. The AI explains; a small deterministic controller acts.
- **Why HPA floor instead of scale?** Two controllers fighting over one field is a classic outage amplifier. Raise the floor, let the HPA own the rest, restore after.
- **Failure scenarios:** controller down (proposals wait, nothing executes; approved items run after restart because state is in Postgres); API server slow (FAILED, no retries loop); operator never answers (EXPIRED); scaling cannot help (BLOCKED at max, DB saturated).
- **Security risks:** shared approver token (until Phase 13), controller compromise (RBAC limits to three HPAs' minReplicas, bounded by `max`), audit in the same DB.
- **Cost:** raising the floor adds pods (billable in a cloud cluster) until restored; cooldown, rate limit and bounds cap the exposure.
- **10M users:** per-service policies as config in git, multi-cluster controller with leader election, approvals via ChatOps with signed identities, SLO-driven (error budget) automation instead of fixed thresholds.
