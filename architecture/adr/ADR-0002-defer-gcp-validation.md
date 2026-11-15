# ADR-0002: Defer GCP validation; build local-first
Status: Accepted (Phase 1)

## Context
The Google account/project (`cloudpulse-ai-poc`) will be set up last. The active gcloud identity on the dev machine is a corporate account that must not be used for this POC.

## Decision
1. Phases that need no GCP access run first: local infra (Compose), services, Pub/Sub emulator, local streaming analytics, dashboard, anomaly detection, AI assistant (rule-based/evidence-only; Vertex AI is an optional adapter), kind-based Kubernetes, self-healing, security, DR drills.
2. GCP-dependent work is isolated behind adapters/profiles (`PUBSUB_EMULATOR_HOST` vs real Pub/Sub; local sink vs BigQuery) so switching needs configuration only.
3. Terraform is written and `fmt`/`validate`d offline; `plan`/`apply` happen only after `scripts/phase1-validate.sh` passes with the personal account, with a cost estimate and explicit approval.
4. Project/billing/IAM validation (the remaining Phase 1 checkpoint) is moved to the "cloud enablement" step at the end.

## Consequences
Faster feedback, zero cost, no credential risk. Cloud-only claims (Workload Identity, real BigQuery latency, RTO on GKE) stay unverified until cloud enablement; docs must label them as such.
