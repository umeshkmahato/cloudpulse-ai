# CI/CD design (Phase 15)

Status key: **VERIFIED** = run on this machine; **AUTHORED** = written and syntax-checked, never executed on GitHub or GCP.
Nothing here has run on GitHub Actions: the repository has no remote (by decision), so every workflow is AUTHORED only.
The same gates run locally via `scripts/ci-local.sh` (VERIFIED).

## Pipeline map
| Workflow | Trigger | Does | Credentials | Status |
|---|---|---|---|---|
| `ci.yml` | PR, push to main | Java tests x7 (matrix), dashboard typecheck/tests/build, `terraform fmt`+`validate` (no backend), kustomize render, Trivy deps/IaC/secrets, build + scan 8 images, kind cluster e2e on main (verify, security-verify, remediation e2e) | none | gates VERIFIED locally; workflow AUTHORED |
| `terraform.yml` | PR touching `infrastructure/**`; manual apply | PR: plan per environment with a **read-only** identity, plan saved as an artifact. Apply: manual, in a protected `<env>-infra` environment, applies the **saved reviewed plan** only | WIF | AUTHORED, off |
| `release.yml` | manual | Build image per service tagged with the commit SHA, Trivy scan **before** push, push to Artifact Registry, sign keyless with cosign | WIF | AUTHORED, off |
| `deploy.yml` | manual | Protected environment approval, verify cosign signatures, record running images, `kustomize edit set image`, `kubectl apply -k`, wait for rollout, **auto-undo on failed rollout** | WIF | AUTHORED, off |
| `rollback.yml` | manual | `rollout undo` for all deployments, or redeploy a chosen released SHA | WIF | AUTHORED, off |

All cloud jobs carry `if: vars.GCP_ENABLED == 'true'`. The variable is unset, so **no job can reach GCP** until someone sets it on purpose.

## Design choices
- **Keyless auth.** Cloud jobs use GitHub OIDC with Workload Identity Federation (the `iam` Terraform module already creates the pool and provider, restricted to this repository). There is no service account key anywhere; `ci-local.sh` fails if a workflow mentions `credentials_json`, `GOOGLE_CREDENTIALS` or `private_key`.
- **Separate identities by blast radius.** Planner (read-only), applier (write, only in the protected infra environment), deployer (push images, deploy to GKE). A pull request from a fork cannot obtain any of them.
- **Immutable artifacts.** Images are tagged with the commit SHA, deployments pin that tag, and the signature is bound to the release workflow identity. `deploy.yml` refuses an unsigned image. The cluster-side half (Binary Authorization / admission) is still open: risk R5 in `security-design.md`.
- **Plan review is a hard gate.** Terraform applies a saved plan file, so what was reviewed is what is applied; Terraform rejects a stale plan. Billable resources stay behind default-off variables (ADR-0001), and `scripts/tf-summary.py` flags them.
- **Progressive environments.** staging then production, each a GitHub Environment with required reviewers (staging 1, production 2 plus a wait timer). These settings live in GitHub, not in the repo, and must be configured by hand.
- **Rollback is a first-class path.** Automatic `rollout undo` on a failed rollout, a manual rollback workflow, and the pre-deploy image list saved as an artifact. See `runbooks/rollback-runbook.md`.
- **Least privilege for workflows.** Top-level `permissions: contents: read`; `id-token: write` only on jobs that authenticate.

## Known gaps (honest list)
1. Workflows have never run on GitHub; expect first-run fixes (action versions, paths, permissions).
2. Third-party actions are pinned to major versions, not commit SHAs. Production practice is SHA pinning plus Dependabot (`.github/dependabot.yml` covers all Maven modules, npm, Actions, Terraform environments and the base image; unverified until it runs on GitHub).
3. The Trivy installer is fetched from GitHub at a pinned tag; a vendored binary or a pinned action would be stronger.
4. There is no canary or progressive delivery; rollout is a rolling update. Argo Rollouts or Cloud Deploy is the next step.
5. No post-deploy smoke test job against a real environment (the kind e2e scripts are the template).
6. Branch protection, required reviewers and environment secrets are manual GitHub settings and unverified.
7. DB migrations (Flyway) run at service start; there is no expand/contract gate, so a rollback after a destructive migration is not safe. Rule: migrations must be backward compatible for one release.

## Interview notes
- **Why a manual deploy workflow instead of deploy-on-merge?** The POC has one human owner and billable infrastructure; auto-deploy is a one-line trigger change once staging exists.
- **Why rollback by `rollout undo` and by SHA?** Undo is seconds and needs no registry; SHA redeploy is deterministic and survives multiple bad releases.
- **Scaling the pipeline to 10M users / many teams:** reusable workflows, per-service pipelines with path filters, remote build cache, a progressive-delivery controller with SLO-based analysis (the anomaly detector is a natural input), and policy-as-code in CI.
- **Cost:** GitHub-hosted minutes only; the kind e2e job is the heaviest (about 15-25 minutes, estimate, not measured on GitHub).
