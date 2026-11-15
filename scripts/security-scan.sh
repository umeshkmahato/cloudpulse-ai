#!/usr/bin/env bash
# Supply-chain and configuration scanning with Trivy: dependencies (Maven, npm), IaC (Terraform + rendered Kubernetes),
# secrets, and container images. Exit code 1 if any finding at or above FAIL_ON (default HIGH) is not suppressed.
# Usage: scripts/security-scan.sh [deps|iac|secrets|images|all]      FAIL_ON=CRITICAL scripts/security-scan.sh images
set -uo pipefail
cd "$(dirname "$0")/.."
command -v trivy >/dev/null || { echo "trivy not installed (brew install trivy)"; exit 2; }
WHAT="${1:-all}"; FAIL_ON="${FAIL_ON:-HIGH}"; OUT=.run/security; mkdir -p $OUT
SEV="CRITICAL,HIGH"; [ "$FAIL_ON" = "CRITICAL" ] && SEV="CRITICAL"
IGN=""; [ -f .trivyignore ] && IGN="--ignorefile .trivyignore"
rc=0
run() { # name, then trivy args
  local name="$1"; shift
  echo "== $name"
  trivy "$@" --severity "$SEV" --exit-code 1 --quiet $IGN --format table > "$OUT/$name.txt" 2>"$OUT/$name.err"; local r=$?
  if [ $r -eq 0 ]; then echo "   clean at $SEV"; else echo "   FINDINGS (see $OUT/$name.txt)"; grep -E "^Total:|^[A-Za-z0-9._/-]+ \(" "$OUT/$name.txt" | head -5 | sed 's/^/   /'; rc=1; fi
  [ -s "$OUT/$name.err" ] && grep -iE "fatal|error" "$OUT/$name.err" | head -2 | sed 's/^/   /'
}

case "$WHAT" in deps|all)
  for d in services/order-service services/inventory-service services/operations-service services/ai-incident-assistant services/remediation-controller services/identity-service data-pipeline/analytics-sink; do
    run "deps-$(basename $d)" fs "$d" --scanners vuln --skip-dirs target
  done
  run deps-dashboard fs frontend/operations-dashboard --scanners vuln --skip-dirs node_modules,dist ;;
esac
case "$WHAT" in secrets|all)
  run secrets fs . --scanners secret --skip-dirs node_modules,target,dist,.terraform,.run,.git --skip-files .env ;;
esac
case "$WHAT" in iac|all)
  run iac-terraform config infrastructure/terraform --skip-dirs .terraform
  for o in local gke; do kubectl kustomize kubernetes/overlays/$o > $OUT/rendered-$o.yaml 2>/dev/null; done
  run iac-kubernetes-gke config $OUT/rendered-gke.yaml ;;
esac
case "$WHAT" in images|all)
  for i in order-service inventory-service operations-service analytics-sink ai-incident-assistant remediation-controller identity-service dashboard; do
    docker image inspect cloudpulse/$i:0.1.0 >/dev/null 2>&1 && run "image-$i" image "cloudpulse/$i:0.1.0" --scanners vuln --ignore-unfixed
  done ;;
esac
echo; [ $rc -eq 0 ] && echo "SECURITY SCAN: CLEAN at $SEV" || echo "SECURITY SCAN: FINDINGS (details in $OUT/)"
exit $rc
