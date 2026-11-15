#!/usr/bin/env bash
# CloudPulse AI - Phase 1 environment validation (READ-ONLY: creates nothing, changes nothing)
# Usage: PROJECT_ID=cloudpulse-ai-poc REGION=asia-south2 ./scripts/phase1-validate.sh
set -uo pipefail

PROJECT_ID="${PROJECT_ID:-cloudpulse-ai-poc}"
REGION="${REGION:-asia-south2}"
PASS=0; WARN=0; FAIL=0
ok()   { printf '  [PASS] %s\n' "$*"; PASS=$((PASS+1)); }
warn() { printf '  [WARN] %s\n' "$*"; WARN=$((WARN+1)); }
bad()  { printf '  [FAIL] %s\n' "$*"; FAIL=$((FAIL+1)); }
hdr()  { printf '\n== %s ==\n' "$*"; }

hdr "1. Local tooling"
check_tool() { # name, required(1/0), version-cmd
  if command -v "$1" >/dev/null 2>&1; then ok "$1: $(eval "$3" 2>&1 | head -1)"
  elif [ "$2" = 1 ]; then bad "$1 not found (required)"; else warn "$1 not found (optional now, needed later)"; fi
}
check_tool gcloud    1 "gcloud --version"
check_tool git       1 "git --version"
check_tool terraform 1 "terraform version"
check_tool docker    1 "docker --version"
check_tool java      1 "java -version"
check_tool mvn       1 "mvn -v"
check_tool node      1 "node --version"
check_tool npm       1 "npm --version"
check_tool kubectl   0 "kubectl version --client"
check_tool kind      0 "kind version"
check_tool gh        0 "gh --version"
if command -v java >/dev/null 2>&1; then
  java -version 2>&1 | grep -q 'version "21' && ok "Java major version is 21" || warn "Java is not 21 (Spring Boot 3 + virtual threads target Java 21)"
fi
command -v docker >/dev/null 2>&1 && { docker info >/dev/null 2>&1 && ok "Docker daemon reachable" || bad "Docker installed but daemon not reachable"; }
command -v docker >/dev/null 2>&1 && { docker compose version >/dev/null 2>&1 && ok "docker compose plugin present" || bad "docker compose plugin missing"; }

command -v gcloud >/dev/null 2>&1 || { hdr "Result"; echo "gcloud missing - cannot continue GCP checks."; exit 1; }

hdr "2. Authentication"
ACCT="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null)"
[ -n "$ACCT" ] && ok "Active account: $ACCT" || bad "No active gcloud account. Run: gcloud auth login"
gcloud auth application-default print-access-token >/dev/null 2>&1 \
  && ok "Application Default Credentials available (needed by Terraform / client libs)" \
  || warn "No ADC. Run: gcloud auth application-default login"
CUR="$(gcloud config get-value project 2>/dev/null)"
[ "$CUR" = "$PROJECT_ID" ] && ok "gcloud default project = $PROJECT_ID" || warn "gcloud default project is '$CUR'. Run: gcloud config set project $PROJECT_ID"

hdr "3. Project"
if gcloud projects describe "$PROJECT_ID" --format='value(projectId,lifecycleState,projectNumber)' >/tmp/cp_proj.txt 2>/tmp/cp_err.txt; then
  ok "Project visible: $(tr '\t' ' ' </tmp/cp_proj.txt)"
  grep -q ACTIVE /tmp/cp_proj.txt || bad "Project lifecycle is not ACTIVE"
else bad "Cannot describe project: $(head -1 /tmp/cp_err.txt)"; fi
ORG="$(gcloud projects get-ancestors "$PROJECT_ID" --format='value(type,id)' 2>/dev/null | tr '\n' ' ')"
echo "  info: ancestry = ${ORG:-none/unknown}"

hdr "4. Billing (status only; no changes)"
BI="$(gcloud billing projects describe "$PROJECT_ID" --format='value(billingEnabled,billingAccountName)' 2>&1)"
if echo "$BI" | grep -qi "True"; then ok "Billing enabled: $BI"
elif echo "$BI" | grep -qi "False"; then bad "Billing NOT enabled (Pub/Sub/BigQuery quotas limited; GKE/Cloud SQL impossible)"
else warn "Could not read billing info (need billing.resourceAssociations.list or Billing Viewer): $(echo "$BI" | head -1)"; fi
echo "  info: billing accounts you can see:"; gcloud billing accounts list --format='table(name,displayName,open)' 2>&1 | sed 's/^/    /' | head -8
echo "  info: existing budgets (needs billing account id):"
BA="$(gcloud billing projects describe "$PROJECT_ID" --format='value(billingAccountName)' 2>/dev/null | sed 's#billingAccounts/##')"
[ -n "$BA" ] && gcloud billing budgets list --billing-account="$BA" --format='table(displayName,amount.specifiedAmount.units,amount.specifiedAmount.currencyCode)' 2>&1 | sed 's/^/    /' | head -8 \
  || echo "    (no billing account linked/visible)"

hdr "5. Your IAM roles on the project"
if [ -n "$ACCT" ]; then
  ROLES="$(gcloud projects get-iam-policy "$PROJECT_ID" --flatten='bindings[].members' \
     --filter="bindings.members:$ACCT" --format='value(bindings.role)' 2>/dev/null)"
  if [ -n "$ROLES" ]; then echo "$ROLES" | sed 's/^/    /'
    echo "$ROLES" | grep -Eq 'roles/(owner|editor)' && ok "Owner/Editor present (broad - fine for POC bootstrap; we will lock down in Phase 13)" || warn "No Owner/Editor; check specific roles above against Phase 2 needs"
  else warn "No direct bindings for $ACCT (may be via group/inheritance, or no getIamPolicy permission)"; fi
fi
echo "  info: testIamPermissions for key actions:"
TOKEN="$(gcloud auth print-access-token 2>/dev/null)"
if [ -n "$TOKEN" ]; then
  curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
   "https://cloudresourcemanager.googleapis.com/v1/projects/${PROJECT_ID}:testIamPermissions" \
   -d '{"permissions":["serviceusage.services.enable","resourcemanager.projects.setIamPolicy","iam.serviceAccounts.create","pubsub.topics.create","bigquery.datasets.create","storage.buckets.create","artifactregistry.repositories.create","secretmanager.secrets.create"]}' \
   | python3 -c 'import sys,json
want=["serviceusage.services.enable","resourcemanager.projects.setIamPolicy","iam.serviceAccounts.create","pubsub.topics.create","bigquery.datasets.create","storage.buckets.create","artifactregistry.repositories.create","secretmanager.secrets.create"]
try: have=set(json.load(sys.stdin).get("permissions",[]))
except Exception: have=set()
[print("    %s %s"%("OK     " if w in have else "MISSING",w)) for w in want]'
fi

hdr "6. APIs currently enabled (read-only list)"
gcloud services list --enabled --project="$PROJECT_ID" --format='value(config.name)' 2>/dev/null | sort > /tmp/cp_apis.txt
echo "  enabled count: $(wc -l </tmp/cp_apis.txt)"
for api in pubsub bigquery storage artifactregistry secretmanager cloudkms monitoring logging iam iamcredentials serviceusage cloudresourcemanager billingbudgets; do
  grep -q "^${api}.googleapis.com$" /tmp/cp_apis.txt && echo "    enabled : ${api}.googleapis.com" || echo "    disabled: ${api}.googleapis.com"
done
echo "  Billable-risk APIs that should stay OFF until approved:"
for api in container sqladmin compute dataflow servicenetworking aiplatform run cloudfunctions; do
  grep -q "^${api}.googleapis.com$" /tmp/cp_apis.txt && warn "${api}.googleapis.com already enabled (enabling is free, resources are not; verify nothing is running)" || echo "    off     : ${api}.googleapis.com"
done

hdr "7. Existing billable resources (should all be empty/none for a clean start)"
for cmd in \
 "gcloud compute instances list --project=$PROJECT_ID --format=value(name)" \
 "gcloud compute addresses list --project=$PROJECT_ID --format=value(name)" \
 "gcloud compute forwarding-rules list --project=$PROJECT_ID --format=value(name)" \
 "gcloud compute routers list --project=$PROJECT_ID --format=value(name)" \
 "gcloud container clusters list --project=$PROJECT_ID --format=value(name)" \
 "gcloud sql instances list --project=$PROJECT_ID --format=value(name)" \
 "gcloud dataflow jobs list --project=$PROJECT_ID --status=active --format=value(id)" ; do
  label="$(echo "$cmd" | awk '{print $2" "$3}')"
  out="$($cmd 2>&1)"
  if echo "$out" | grep -qiE 'has not been used|disabled|not enabled|PERMISSION_DENIED|ERROR'; then echo "    skip    : $label (API off or no permission)"
  elif [ -z "$out" ]; then ok "$label: none"
  else warn "$label has resources: $(echo "$out" | tr '\n' ',')"; fi
done

hdr "8. Region availability"
echo "  Target region: $REGION"
gcloud compute regions describe "$REGION" --project="$PROJECT_ID" --format='value(name,status)' 2>/dev/null \
  | grep -q UP && ok "Compute region $REGION is UP" || warn "Could not confirm via Compute API (API may be off). Service-by-service check done in Phase 2."
gcloud artifacts locations list --project="$PROJECT_ID" 2>/dev/null | grep -qx "$REGION" && ok "Artifact Registry supports $REGION" || warn "Artifact Registry location check inconclusive"
gcloud pubsub --help >/dev/null 2>&1 && echo "  info: Pub/Sub is global; BigQuery dataset location and GCS bucket location chosen in Phase 2"

hdr "Result"
echo "PASS=$PASS WARN=$WARN FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && echo "Checkpoint 1: READY (review warnings)" || echo "Checkpoint 1: NOT READY - fix FAIL items and re-run"
exit $FAIL
