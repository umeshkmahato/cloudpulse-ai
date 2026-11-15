#!/usr/bin/env bash
# Safe plan: fmt check + validate + plan to a file + human summary with BILLABLE-resource warnings.
# Usage: scripts/tf-plan.sh dev [extra terraform plan args]   (needs gcloud ADC for the personal account)
set -euo pipefail
ENV="${1:?usage: tf-plan.sh <dev|staging|prod> [args]}"; shift || true
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/infrastructure/terraform/environments/$ENV"
OUT="$ROOT/.run/$ENV.tfplan"
mkdir -p "$ROOT/.run"
[ -d "$DIR" ] || { echo "unknown environment $ENV"; exit 1; }

terraform -chdir="$DIR" fmt -check -recursive ../..
terraform -chdir="$DIR" init -input=false
terraform -chdir="$DIR" validate
terraform -chdir="$DIR" plan -input=false -out="$OUT" "$@"
"$ROOT/scripts/tf-summary.py" "$OUT" "$DIR"
echo; echo "Plan saved to $OUT. Review it, read architecture/cost-estimate.md, then run: scripts/tf-apply.sh $ENV"
