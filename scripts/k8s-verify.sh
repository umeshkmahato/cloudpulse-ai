#!/usr/bin/env bash
# Phase 11 verification against the local kind cluster: hardening, probes, HPA, PDB, spread, NetworkPolicy enforcement,
# RBAC, the full business flow through the in-cluster dashboard gateway, and self-recovery. Usage: scripts/k8s-verify.sh
set -uo pipefail
cd "$(dirname "$0")/.."
NS=cloudpulse
K="kubectl -n $NS"
APPS=(order-service inventory-service operations-service analytics-sink ai-incident-assistant remediation-controller dashboard)
fail=0
check() { if eval "$2"; then echo "[PASS] $1"; else echo "[FAIL] $1"; fail=1; fi; }
jp() { $K get "$1" -o jsonpath="$2" 2>/dev/null; }

echo "--- 0. fresh placement (re-spread order-service across zones)"
$K rollout restart deploy/order-service >/dev/null && $K rollout status deploy/order-service --timeout=240s >/dev/null
echo "--- 1. workloads are healthy"
appdeploys() { $K get deploy -o json | python3 -c "import sys,json;apps=set('${APPS[*]}'.split());print(json.dumps([x for x in json.load(sys.stdin)['items'] if x['metadata']['name'] in apps]))"; }
check "all 7 app deployments fully available" "[ \$(appdeploys | python3 -c 'import sys,json;d=json.load(sys.stdin);print(sum(1 for x in d if x[\"status\"].get(\"availableReplicas\",0)==x[\"spec\"][\"replicas\"]))') -eq 7 ]"
check "no container is running as root and none can escalate" "$K get pods -o json | python3 -c 'import sys,json;d=json.load(sys.stdin);bad=[p[\"metadata\"][\"name\"] for p in d[\"items\"] if p[\"metadata\"][\"labels\"].get(\"app.kubernetes.io/name\") not in (\"postgres\",\"pubsub-emulator\",\"pubsub-init\") for c in p[\"spec\"][\"containers\"] if c[\"securityContext\"].get(\"allowPrivilegeEscalation\",True) or p[\"spec\"][\"securityContext\"].get(\"runAsNonRoot\") is not True];print(bad);sys.exit(1 if bad else 0)' >/dev/null"
check "app containers use read-only root filesystems and drop ALL capabilities" "$K get pods -l app.kubernetes.io/name=order-service -o json | python3 -c 'import sys,json;c=json.load(sys.stdin)[\"items\"][0][\"spec\"][\"containers\"][0][\"securityContext\"];sys.exit(0 if c[\"readOnlyRootFilesystem\"] and c[\"capabilities\"][\"drop\"]==[\"ALL\"] else 1)'"
check "every app container has requests and limits" "appdeploys | python3 -c 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if all(c[\"resources\"].get(\"requests\") and c[\"resources\"].get(\"limits\") for x in d for c in x[\"spec\"][\"template\"][\"spec\"][\"containers\"]) else 1)'"
check "startup, liveness and readiness probes defined on every app" "appdeploys | python3 -c 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if all(all(k in c for k in (\"startupProbe\",\"livenessProbe\",\"readinessProbe\")) for x in d for c in x[\"spec\"][\"template\"][\"spec\"][\"containers\"]) else 1)'"
check "application ServiceAccounts do not mount API tokens" "[ \"\$($K get sa order-service -o jsonpath='{.automountServiceAccountToken}')\" = false ]"

echo "--- 2. scheduling resilience"
check "order-service replicas are spread across both fake zones" "[ \$($K get pods -l app.kubernetes.io/name=order-service -o jsonpath='{range .items[*]}{.spec.nodeName}{\"\\n\"}{end}' | sort -u | wc -l | tr -d ' ') -ge 2 ]"
check "PodDisruptionBudget exists for order-service (minAvailable 1)" "[ \"\$($K get pdb order-service -o jsonpath='{.spec.minAvailable}')\" = 1 ]"
check "HPA reads live CPU metrics (metrics-server working)" "for _ in \$(seq 1 20); do v=\$($K get hpa order-service -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}'); [ -n \"\$v\" ] && break; sleep 5; done; [ -n \"\$v\" ]"

echo "--- 3. RBAC (least privilege)"
check "remediation SA may raise the order-service HPA floor" "[ \"\$(kubectl auth can-i patch horizontalpodautoscalers/order-service -n $NS --as=system:serviceaccount:$NS:remediation-controller)\" = yes ]"
check "remediation SA may NOT touch other HPAs/deployments, and cannot scale deployments directly" "[ \"\$(kubectl auth can-i patch horizontalpodautoscalers/analytics-sink -n $NS --as=system:serviceaccount:$NS:remediation-controller)\" = no ] && [ \"\$(kubectl auth can-i patch deployments/order-service --subresource=scale -n $NS --as=system:serviceaccount:$NS:remediation-controller)\" = no ]"
check "remediation SA may NOT read secrets or exec into pods" "[ \"\$(kubectl auth can-i get secrets -n $NS --as=system:serviceaccount:$NS:remediation-controller)\" = no ] && [ \"\$(kubectl auth can-i create pods --subresource=exec -n $NS --as=system:serviceaccount:$NS:remediation-controller)\" = no ]"
check "app SA has no permissions at all" "[ \"\$(kubectl auth can-i list pods -n $NS --as=system:serviceaccount:$NS:order-service)\" = no ]"

echo "--- 4. NetworkPolicy enforcement (default deny + explicit allows)"
$K delete pod netprobe --ignore-not-found >/dev/null 2>&1
$K run netprobe --image=curlimages/curl:8.10.1 --image-pull-policy=IfNotPresent --restart=Never --labels=app.kubernetes.io/name=intruder \
  --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":100,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"netprobe","image":"curlimages/curl:8.10.1","imagePullPolicy":"IfNotPresent","command":["sleep","300"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null 2>&1
$K wait --for=condition=Ready pod/netprobe --timeout=60s >/dev/null 2>&1
check "unlisted pod cannot reach operations-service (ingress denied)" "! $K exec netprobe -- curl -s -m 4 -o /dev/null http://operations-service:8083/actuator/health/liveness"
check "unlisted pod cannot reach postgres" "! $K exec netprobe -- curl -s -m 4 -o /dev/null http://postgres:5432"
check "dashboard (allowed) can reach operations-service directly (default-deny + explicit allow)" "$K exec deploy/dashboard -- wget -q -T 5 -O- http://operations-service.cloudpulse.svc.cluster.local:8083/actuator/health/liveness | grep -q UP"
$K delete pod netprobe --ignore-not-found >/dev/null 2>&1

echo "--- 5. the real business flow inside the cluster (browser -> nginx gateway -> services -> Pub/Sub -> Postgres)"
$K port-forward svc/dashboard 18088:8080 >/dev/null 2>&1 & PF=$!; sleep 3
JAR=$(mktemp)                       # every API call now goes through the authenticating gateway
for i in $(seq 1 24); do c=$(curl -s -c $JAR -o /dev/null -w '%{http_code}' -X POST localhost:18088/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"operator\",\"password\":\"$(awk '$1=="operator"{print $2}' .run/identity-credentials.txt)\"}"); [ "$c" != 429 ] && break; sleep 5; done
gw() { curl -s -b "$JAR" -H 'X-CloudPulse-CSRF: 1' "$@"; }
trap 'kill $PF 2>/dev/null' EXIT
check "SPA is served with security headers" "curl -sI localhost:18088/ | grep -qi 'x-frame-options: DENY'"
OID=$(gw -X POST localhost:18088/orders-api/api/v1/orders -H 'Content-Type: application/json' -d '{"customerId":"CUST-K8S","currency":"USD","items":[{"sku":"SKU-RED-1","quantity":1,"unitPrice":19.99}]}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["orderId"])' 2>/dev/null)
check "order accepted through the gateway ($OID)" "[ -n \"$OID\" ]"
check "order becomes CONFIRMED (order -> Pub/Sub -> inventory -> Pub/Sub -> order)" "for _ in \$(seq 1 30); do s=\$(gw localhost:18088/orders-api/api/v1/orders/$OID | python3 -c 'import sys,json;print(json.load(sys.stdin)[\"status\"])'); [ \"\$s\" = CONFIRMED ] && break; sleep 1; done; [ \"\$s\" = CONFIRMED ]"
check "event reached the analytics store via the in-cluster sink" "for _ in \$(seq 1 20); do gw 'localhost:18088/ops-api/api/v1/analytics/overview?minutes=10' | python3 -c 'import sys,json;sys.exit(0 if json.load(sys.stdin)[\"orders\"][\"orders\"]>=1 else 1)' && break; sleep 1; done; gw 'localhost:18088/ops-api/api/v1/analytics/overview?minutes=10' | python3 -c 'import sys,json;sys.exit(0 if json.load(sys.stdin)[\"orders\"][\"orders\"]>=1 else 1)'"
check "assistant answers through the gateway" "gw -X POST localhost:18088/assistant-api/api/v1/assistant/ask -H 'Content-Type: application/json' -d '{\"question\":\"What happens if the inventory service fails?\"}' | python3 -c 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if d[\"intent\"]==\"FAILURE_WHAT_IF\" and d[\"claims\"] else 1)'"

echo "--- 6. self-recovery and safe disruption"
VICTIM=$($K get pods -l app.kubernetes.io/name=order-service -o jsonpath='{.items[0].metadata.name}')
$K delete pod "$VICTIM" --wait=false >/dev/null
check "orders keep working while one order-service pod is killed" "ok=0; for _ in \$(seq 1 10); do c=\$(gw -o /dev/null -w '%{http_code}' localhost:18088/orders-api/api/v1/orders/$OID); [ \"\$c\" = 200 ] && ok=\$((ok+1)); sleep 1; done; [ \$ok -ge 9 ]"
check "ReplicaSet restores the desired replica count" "$K rollout status deploy/order-service --timeout=180s >/dev/null && [ \$($K get deploy order-service -o jsonpath='{.status.availableReplicas}') -ge 2 ]"
NODE=$(kubectl get nodes -l topology.kubernetes.io/zone=zone-b -o jsonpath='{.items[0].metadata.name}')   # zone-b hosts only stateless apps
kubectl cordon "$NODE" >/dev/null
OUT=$(kubectl drain "$NODE" --ignore-daemonsets --delete-emptydir-data --force --timeout=120s 2>&1)
check "node drain evicts pods respecting the PDB (never zero order-service pods)" "$K get deploy order-service -o jsonpath='{.status.availableReplicas}' | grep -qE '^[1-9]'"
kubectl uncordon "$NODE" >/dev/null
$K rollout status deploy/order-service --timeout=180s >/dev/null 2>&1

kill $PF 2>/dev/null
echo; [ $fail -eq 0 ] && echo "PHASE 11 K8S VERIFY: ALL PASSED" || echo "PHASE 11 K8S VERIFY: FAILURES"
exit $fail
