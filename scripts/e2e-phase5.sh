#!/usr/bin/env bash
# Phase 5 end-to-end check: Order -> Pub/Sub -> Inventory -> Pub/Sub, plus idempotency and telemetry.
# Prereqs: docker compose up -d; mvn -q -DskipTests package in each service.  Usage: scripts/e2e-phase5.sh
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
export PUBSUB_EMULATOR_HOST=localhost:8085
PS="http://localhost:8085/v1/projects/${GCP_PROJECT_ID:-cloudpulse-ai-poc}"
RUN=.run; mkdir -p $RUN
PIDS=()
cleanup() { for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done; }
trap cleanup EXIT

start() { # name port
  java -jar services/$1/target/$1-0.1.0.jar > $RUN/$1.log 2>&1 & PIDS+=($!)
  for _ in $(seq 1 60); do curl -sf localhost:$2/actuator/health >/dev/null && return 0; sleep 1; done
  echo "FAILED to start $1 (see $RUN/$1.log)"; exit 1
}
fail=0
check() { if eval "$2"; then echo "[PASS] $1"; else echo "[FAIL] $1"; fail=1; fi; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
avail() { curl -s localhost:8082/api/v1/inventory/$1 | json 'd["available"]'; }
pull() { curl -s -X POST -H 'Content-Type: application/json' $PS/subscriptions/$1:pull -d '{"maxMessages":10}'; }
drain() { local r; r=$(pull "$1"); local ids; ids=$(echo "$r" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(json.dumps([m["ackId"] for m in d.get("receivedMessages",[])]))'); curl -s -X POST -H 'Content-Type: application/json' $PS/subscriptions/$1:acknowledge -d "{\"ackIds\":$ids}" >/dev/null; }

start order-service 8081; start inventory-service 8082; start operations-service 8083
drain inventory-updated-analytics-sub

echo "--- 1. happy path"
before=$(avail SKU-RED-1)
CORR="e2e-$RANDOM"
resp=$(curl -s -X POST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -H "X-Correlation-ID: $CORR" \
  -d '{"customerId":"CUST-E2E","currency":"USD","items":[{"sku":"SKU-RED-1","quantity":2,"unitPrice":19.99}]}')
OID=$(echo "$resp" | json 'd["orderId"]')
sleep 4
after=$(avail SKU-RED-1)
check "stock decremented by 2 ($before -> $after)" "[ $((before-2)) -eq $after ]"
msgs=$(pull inventory-updated-analytics-sub)
check "inventory.updated RESERVED for $OID with correlation $CORR" \
  "echo '$msgs' | python3 -c 'import sys,json,base64;ms=json.load(sys.stdin).get(\"receivedMessages\",[]);ok=any((lambda e:e[\"data\"][\"orderId\"]==\"$OID\" and e[\"data\"][\"status\"]==\"RESERVED\" and e[\"correlationId\"]==\"$CORR\")(json.loads(base64.b64decode(m[\"message\"][\"data\"]))) for m in ms);sys.exit(0 if ok else 1)'"

echo "--- 2. insufficient stock"
low_before=$(avail SKU-LOW-3)
r2=$(curl -s -X POST localhost:8081/api/v1/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"CUST-E2E","currency":"USD","items":[{"sku":"SKU-LOW-3","quantity":5,"unitPrice":9.99}]}')
sleep 4
check "stock unchanged for rejected order" "[ $low_before -eq $(avail SKU-LOW-3) ]"
check "reservation REJECTED in DB" "docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc \"select status||':'||reason from inventory.reservations where order_id='$(echo "$r2" | json 'd["orderId"]')'\" | grep -q 'REJECTED:INSUFFICIENT_STOCK:SKU-LOW-3'"

echo "--- 3. duplicate delivery (same event re-published)"
payload=$(docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc "select payload from orders.outbox_events where payload like '%$OID%' limit 1")
b64=$(printf '%s' "$payload" | base64 | tr -d '\n')
for _ in 1 2; do curl -s -X POST -H 'Content-Type: application/json' $PS/topics/orders-created:publish -d "{\"messages\":[{\"data\":\"$b64\"}]}" >/dev/null; done
sleep 4
check "stock not decremented again after 2 redeliveries" "[ $after -eq $(avail SKU-RED-1) ]"
check "duplicates counter > 0" "curl -s localhost:8082/actuator/prometheus | grep -E '^inventory_events_duplicates_total' | grep -vq ' 0.0'"

echo "--- 4. telemetry via Pub/Sub"
curl -s -o /dev/null -X POST localhost:8083/api/v1/telemetry/metrics -H 'Content-Type: application/json' \
  -d '{"service":"order-service","metric":"cpu_pct","value":92.5,"unit":"pct"}'
curl -s -o /dev/null -X POST localhost:8083/api/v1/telemetry/metrics -H 'Content-Type: application/json' \
  -d '{"service":"order-service","metric":"latency_ms","value":300,"unit":"ms"}'
sleep 3
check "latest metrics stored and labelled synthetic" "curl -s localhost:8083/api/v1/metrics/latest | json 'all(s[\"synthetic\"] for s in d) and len(d)>=2' | grep -q True"
check "order-service DEGRADED with reason (cpu 92.5 > 85)" "curl -s localhost:8083/api/v1/services/health | json '[h for h in d if h[\"service\"]==\"order-service\"][0][\"status\"]' | grep -q DEGRADED"
check "unknown metric rejected with 400" "[ \$(curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8083/api/v1/telemetry/metrics -H 'Content-Type: application/json' -d '{\"service\":\"x\",\"metric\":\"bogus\",\"value\":1}') = 400 ]"

echo; [ $fail -eq 0 ] && echo "PHASE 5 E2E: ALL PASSED" || echo "PHASE 5 E2E: FAILURES"
exit $fail
