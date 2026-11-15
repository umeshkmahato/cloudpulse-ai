# ADR-0004: CI/CD is keyless, gated by default, and runs the same checks locally

Status: accepted (Phase 15)

Context: no GCP project exists yet (ADR-0002) and the repository has no remote. Pipelines must be reviewable now without being able to spend money or hold credentials.

Decision:
1. Authenticate with GitHub OIDC and Workload Identity Federation only; no key files, and CI checks for their absence.
2. Every job that touches GCP is guarded by the repository variable `GCP_ENABLED`, unset by default.
3. Terraform apply consumes a saved, reviewed plan inside a protected environment.
4. All non-cloud gates live in one local script (`scripts/ci-local.sh`) so they can be verified without GitHub.

Consequences: the workflows are verified only by syntax and by running their commands locally; first real runs will need fixes. Supply-chain depth (SHA pinning, Binary Authorization, canary rollout) is listed as follow-up in `architecture/cicd.md`.
