#!/usr/bin/env bash
# Guarded apply of a REVIEWED plan file. Never plans on its own and never runs with -auto-approve.
# Usage: scripts/tf-apply.sh dev      (billable resources additionally need I_ACCEPT_COSTS=yes)
set -euo pipefail
ENV="${1:?usage: tf-apply.sh <dev|staging|prod>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/infrastructure/terraform/environments/$ENV"
PLAN="$ROOT/.run/$ENV.tfplan"
[ -f "$PLAN" ] || { echo "No saved plan. Run scripts/tf-plan.sh $ENV first."; exit 1; }

set +e; "$ROOT/scripts/tf-summary.py" "$PLAN" "$DIR"; rc=$?; set -e
if [ $rc -eq 3 ] && [ "${I_ACCEPT_COSTS:-}" != "yes" ]; then
  echo; echo "REFUSING: plan contains billable or destructive changes. Review architecture/cost-estimate.md,"
  echo "then re-run with I_ACCEPT_COSTS=yes if (and only if) you approve them."; exit 2
fi
[ "$ENV" = "prod" ] && echo "PRODUCTION apply."
read -r -p "Type 'apply $ENV' to continue: " answer
[ "$answer" = "apply $ENV" ] || { echo "aborted"; exit 1; }
terraform -chdir="$DIR" apply -input=false "$PLAN"
