#!/usr/bin/env bash
# Runs the same gates as .github/workflows/ci.yml on this machine (no cloud credentials). Usage: scripts/ci-local.sh [fast]
# `fast` skips the Trivy scans. Exit code is non-zero if any gate fails.
set -uo pipefail
cd "$(dirname "$0")/.."
FAST="${1:-}"; fail=0; pass=0
gate() { local n="$1"; shift; if "$@" >.run/ci-local-last.log 2>&1; then echo "[PASS] $n"; pass=$((pass+1)); else echo "[FAIL] $n  (tail of log:)"; tail -8 .run/ci-local-last.log | sed 's/^/       /'; fail=$((fail+1)); fi; }
mkdir -p .run
for m in services/order-service services/inventory-service services/operations-service services/ai-incident-assistant services/remediation-controller services/identity-service data-pipeline/analytics-sink; do
  gate "java verify: $m" bash -c "cd $m && mvn -B -q verify"
done
gate "dashboard typecheck, tests, build" bash -c "cd frontend/operations-dashboard && npx tsc -b --noEmit && npx vitest run && npm run build"
gate "terraform fmt" terraform -chdir=infrastructure/terraform fmt -check -recursive
for e in dev staging prod; do
  gate "terraform validate: $e" bash -c "d=infrastructure/terraform/environments/$e; terraform -chdir=\$d init -backend=false -input=false && terraform -chdir=\$d validate"
done
for o in local gke; do gate "kustomize render: $o" kubectl kustomize kubernetes/overlays/$o; done
gate "workflow YAML parses" bash -c 'for f in .github/workflows/*.yml; do ruby -ryaml -e "YAML.load_file(ARGV[0])" "$f" || exit 1; done'
gate "no workflow uses a long-lived cloud key" bash -c '! grep -rnE "credentials_json|GOOGLE_CREDENTIALS|private_key" .github/'
gate "cloud jobs are gated by GCP_ENABLED" bash -c 'for f in release deploy rollback terraform; do grep -q "GCP_ENABLED" .github/workflows/$f.yml || exit 1; done'
if [ "$FAST" != "fast" ]; then
  gate "trivy: dependencies" scripts/security-scan.sh deps
  gate "trivy: IaC + rendered Kubernetes" scripts/security-scan.sh iac
  gate "trivy: secrets" scripts/security-scan.sh secrets
fi
echo; echo "ci-local: $pass passed, $fail failed"; exit $fail
