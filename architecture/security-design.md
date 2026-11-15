# Security design (Phase 13)

Scope: the CloudPulse platform as built (local kind cluster verified; GCP pieces authored and validated offline). Anything not
verified is marked **UNVERIFIED**. Verification: `scripts/k8s-security-verify.sh` (45 checks), `scripts/security-scan.sh`, plus unit tests.

## Trust boundaries
```
Internet/Browser ──HTTPS(LB, UNVERIFIED)──▶ [ nginx gateway ] ──auth_request──▶ [ identity-service ]
                                                │  verified JWT + identity headers
                                                ▼
                    order / inventory / operations / assistant / remediation-controller      (cluster network, default-deny)
                                                │
                          Postgres (private IP)  ·  Pub/Sub  ·  Kubernetes API (controller only)
```
1. **Edge** (browser to gateway): only the SPA and `/auth/{login,logout,me}` are public. Everything else needs a session.
2. **Gateway to services**: services are `ClusterIP`, reachable only from the gateway and named peers (NetworkPolicy default deny).
3. **Services to data**: Postgres/Pub/Sub only from the apps that need them; Cloud SQL is private-IP only with TLS enforced (Terraform).
4. **Controller to Kubernetes API**: one pod, one RBAC role (patch three named HPAs), network-restricted.

## Authentication and authorization
| Control | Implementation | Verified |
|---|---|---|
| Identity | `identity-service`: bcrypt-hashed users from a Secret (plaintext refused at startup), RS256 JWT (30 min), signing key from a Secret | yes |
| Session | HttpOnly + SameSite=Strict cookie (`Secure` in the GKE overlay); JavaScript never sees the token | yes |
| Policy decision point | nginx `auth_request` to `/auth/verify`: role per (method, path), **default deny**, actuator never reachable, path tricks (`..`, `%2e`, `//`, `\`, control chars) rejected before matching | yes |
| Roles | VIEWER (read, ask assistant) < OPERATOR (place orders, approve/reject remediations) < ADMIN (kill switch, telemetry injection, inventory writes) | yes |
| CSRF | SameSite=Strict **plus** required `X-CloudPulse-CSRF` header on every state-changing call | yes |
| Defence in depth | Gateway forwards a verified JWT; the remediation controller verifies it again (RS256 only, issuer, audience, expiry, role). A tampered payload, HS256 confusion, `alg:none`, forged signature, wrong issuer/audience and expired tokens are all rejected (unit + cluster tests) | yes |
| Brute force | nginx rate limit on `/auth/login` (10/min, burst 5) + per-account lockout (5 failures) + per-IP threshold (30); identical response for unknown user and wrong password; bcrypt verified against a dummy hash for unknown users | yes |
| Attribution | Remediation decisions use the verified token subject, never a form field; every login/denial is an append-only audit row | yes |

## Infrastructure and platform controls
| Area | Control | Status |
|---|---|---|
| IAM | One service account per workload, least-privilege roles, GitHub OIDC via Workload Identity Federation (no keys), Workload Identity for pods | Terraform validated, **UNVERIFIED** in GCP |
| Secrets | Never in git (scan + `.gitignore`); k8s Secrets created at deploy time; Secret Manager containers in Terraform; env vars use `secretKeyRef` only (checked) | local verified |
| Encryption at rest | GCP default encryption; optional CMEK via the `kms` module (Cloud SQL, BigQuery, GCS) | **UNVERIFIED**, gated off |
| Encryption in transit | Cloud SQL `ENCRYPTED_ONLY`; Pub/Sub/Google APIs TLS; browser HTTPS at the LB (cookie `Secure`) | **UNVERIFIED**; see risk R4 for in-cluster traffic |
| Network | Default-deny NetworkPolicies, explicit per-flow allows, private cluster + private DB (Terraform), no external LB in the default plan | local verified |
| Workload hardening | Non-root, read-only rootfs, no privilege escalation, all caps dropped, seccomp, PSA restricted (baseline locally), SA tokens off | verified |
| Admission | Native ValidatingAdmissionPolicy: resource limits, no `:latest`, no hostNetwork/hostPID/hostPath/privileged, **image-registry allow-list** (our registry only on GKE) | verified (deny + allow cases) |
| Audit | Append-only `remediation_audit` and `auth_audit` (DB triggers); Cloud Audit Logs sink to GCS (Terraform) | triggers verified; GCP **UNVERIFIED** |
| Supply chain | `scripts/security-scan.sh` (Trivy): Maven/npm dependencies, IaC (Terraform + rendered Kubernetes), secrets and all 8 images are **clean at CRITICAL/HIGH**. Fixes: Spring Boot 3.3.5 to 3.5.14 plus patch overrides (Jackson, Tomcat, Spring Framework, Postgres driver, Micrometer, Guava, Spring Data, react-router), pinned and trimmed nginx base. Two documented exceptions in `.trivyignore` (Spring Framework 7-only fixes in code paths we do not use; two Alpine libs pending a base-image rebuild) | verified |

## Threat model (STRIDE, highest-value assets)
Assets: customer orders (integrity), the ability to scale/alter the cluster (controller), operator identities, telemetry used for decisions.

| # | Threat | Where | Mitigation | Residual |
|---|---|---|---|---|
| S1 | Stolen/forged session token | Browser, gateway | HttpOnly cookie (no XSS theft), RS256 with pinned alg, 30 min TTL, tampering tests | No revocation list: a stolen cookie works until expiry (R1) |
| S2 | Credential stuffing | `/auth/login` | Edge rate limit, per-account lockout, uniform errors, bcrypt | Account lockout can be abused to lock a user out (accepted; per-IP threshold higher) |
| T1 | Request smuggling / path normalisation mismatch to reach actuator | Gateway vs backend | Raw URI sanity check, default deny, actuator not in policy, only `/api/` prefixes allowed | Depends on nginx and Spring agreeing on normalisation for allowed paths |
| T2 | Event tampering on Pub/Sub | Messaging | IAM per-topic publisher/subscriber (Terraform), schema contract tests, idempotent consumers | Emulator locally is unauthenticated |
| R1 | Operator denies approving a scale-out | Controller | Verified identity on every decision, append-only audit | Audit is in the application DB; ship to Cloud Logging/locked bucket (planned) |
| I1 | Secret leakage via git/env/logs | Repo, pods | Secret scanning, `secretKeyRef`, no secrets in ConfigMaps, audit never stores tokens/passwords (tested) | Terraform state holds generated secrets: remote state with CMEK and tight IAM |
| I2 | Information disclosure through errors | APIs | RFC 7807 problems without stack traces, `include-stacktrace: never`, `server_tokens off` | |
| D1 | API flooding | Gateway | `limit_req` (20 r/s per IP), body limit 64k, per-service HPA | No WAF/Cloud Armor yet (GKE) |
| D2 | Runaway automation | Controller | Allow-list, bounds, cooldown, hourly cap, kill switch, human approval by default | |
| E1 | Compromised pod pivots | Cluster | Non-root, read-only fs, no SA token, default-deny network, admission rules | Shared kernel; gVisor/sandbox not used |
| E2 | Controller abused to harm the cluster | Controller | RBAC limited to `patch` on 3 named HPAs; cannot scale deployments, read secrets or exec; bounded by HPA max | A compromised controller could keep the floor high (cost) until noticed |
| E3 | Prompt injection against the AI assistant | Assistant | Read-only, no tools or write path, evidence-only answers, output validator (numbers/citations/commands), no route to the controller | LLM path is UNVERIFIED |

## Accepted risks and open items
- **R1** No token revocation/refresh rotation; add short-lived access tokens with refresh and a revocation list, or move to IAP/OIDC.
- **R2** Demo users are file-based. Production: Google identity via IAP/OIDC, groups mapped to roles.
- **R3** Single signing key without rotation; rotate with JWKS key sets (`kid` already supported).
- **R4** In-cluster service-to-service traffic is plaintext HTTP behind NetworkPolicies. Production: Cloud Service Mesh/Istio mTLS or a SPIFFE-style identity. Not done because the cost/complexity outweighs the POC benefit.
- **R5** No admission-time image signature check (Binary Authorization / cosign); the registry allow-list is the interim control (Phase 15 adds signing).
- **R6** Cloud Armor/WAF and DDoS controls are not part of the default (cost-gated) plan.
- **R7** Audit tables live in the app DB. Export to a locked, retained destination.

## Interview notes
- **Why a policy decision point at the gateway instead of Spring Security in every service?** One place to audit and change policy, uniform behaviour for six services in two languages; but a gateway-only design is brittle, so the controller (the dangerous one) verifies tokens itself and the network is default-deny.
- **Why HttpOnly cookie, not a bearer token in localStorage?** XSS cannot read the cookie; SameSite+CSRF header covers cross-site requests. The trade-off is a stateful-feeling session and CSRF care.
- **Why RS256 and JWKS?** Services verify without sharing a secret; keys rotate by `kid`; no HS256 confusion because the algorithm is pinned.
- **What would you do for 10M users?** Managed identity (IAP/Identity Platform), short-lived tokens with refresh, a service mesh with mTLS and authorization policies, WAF/Cloud Armor, per-tenant data isolation, centralised audit in a locked sink, policy-as-code (OPA/Gatekeeper or VAP) in CI.
- **Scaling limits/failure:** identity-service is on the request path (auth_request): cache decisions (short TTL) or verify JWTs at the gateway with a JWKS module; if identity is down the API fails closed by design.
- **Cost:** none of this adds cloud cost by itself; IAP/Armor/mesh would.
