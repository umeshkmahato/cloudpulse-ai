#!/usr/bin/env bash
# Phase 6 end-to-end: full order lifecycle over Pub/Sub, fan-out, failure handling and DLQ replay.
# Prereqs: docker compose up -d; services packaged (mvn -DskipTests package).  Usage: scripts/e2e-phase6.sh
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
export PUBSUB_EMULATOR_HOST=localhost:8085
PS="http://localhost:8085/v1/projects/${GCP_PROJECT_ID:-cloudpulse-ai-poc}"
RUN=.run; mkdir -p $RUN
PIDS=()
trap 'for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done' EXIT
start() { java -jar services/$1/target/$1-0.1.0.jar > $RUN/$1.log 2>&1 & PIDS+=($!)
  for _ in $(seq 1 60); do curl -sf localhost:$2/actuator/health >/dev/null && return 0; sleep 1; done
  echo "FAILED to start $1 (see $RUN/$1.log)"; exit 1; }
fail=0
check() { if eval "$2"; then echo "[PASS] $1"; else echo "[FAIL] $1"; fail=1; fi; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
order() { curl -s -X POST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -H "X-Correlation-ID: $2" \
  -d "{\"customerId\":\"CUST-E2E6\",\"currency\":\"USD\",\"items\":[{\"sku\":\"$1\",\"quantity\":$3,\"unitPrice\":10.00}]}" | json 'd["orderId"]'; }
status_of() { curl -s localhost:8081/api/v1/orders/$1 | json 'd["status"]+"|"+str(d.get("statusReason"))'; }
wait_status() { for _ in $(seq 1 20); do s=$(status_of $1); [ "${s%%|*}" != "PENDING" ] && { echo "$s"; return; }; sleep 1; done; echo "$s"; }

start order-service 8081; start inventory-service 8082

echo "--- 1. lifecycle: PENDING -> CONFIRMED"
O1=$(order SKU-BLUE-2 life-1 1)
check "order $O1 starts PENDING or already CONFIRMED" "[[ \$(status_of $O1) == PENDING* || \$(status_of $O1) == CONFIRMED* ]]"
check "order $O1 becomes CONFIRMED via inventory.updated" "[[ \$(wait_status $O1) == CONFIRMED* ]]"

echo "--- 2. lifecycle: PENDING -> REJECTED with reason"
O2=$(order SKU-LOW-3 life-2 99)
check "order $O2 REJECTED with INSUFFICIENT_STOCK" "[[ \$(wait_status $O2) == REJECTED\|INSUFFICIENT_STOCK:SKU-LOW-3 ]]"

echo "--- 3. fan-out: analytics subscription also received both events"
n=$(curl -s -X POST -H 'Content-Type: application/json' $PS/subscriptions/inventory-updated-analytics-sub:pull -d '{"maxMessages":50,"returnImmediately":true}' | grep -o '"ackId"' | wc -l | tr -d ' ')
check "analytics-sub holds >= 2 events independent of order-sub (found $n)" "[ $n -ge 2 ]"

echo "--- 4. replayed duplicate inventory.updated does not change the order"
DUP=$(docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc "select payload from inventory.outbox_events where payload like '%$O2%' limit 1")
b64=$(printf '%s' "$DUP" | base64 | tr -d '\n')
curl -s -X POST -H 'Content-Type: application/json' $PS/topics/inventory-updated:publish -d "{\"messages\":[{\"data\":\"$b64\"}]}" >/dev/null
sleep 3
check "order $O2 still REJECTED; duplicate counter incremented" "[[ \$(status_of $O2) == REJECTED* ]] && curl -s localhost:8081/actuator/prometheus | grep -E '^orders_events_duplicates_total' | grep -vq ' 0.0'"

echo "--- 5. unknown order -> retries -> dead-letter"
GHOST='{"eventId":"ghost-'$RANDOM'","eventType":"inventory.updated","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z","correlationId":"ghost","source":"inventory-service","data":{"orderId":"ORD-GHOST0000000","status":"RESERVED","reason":null,"items":[]}}'
curl -s -X POST -H 'Content-Type: application/json' $PS/topics/inventory-updated:publish -d "{\"messages\":[{\"data\":\"$(printf '%s' "$GHOST" | base64 | tr -d '\n')\"}]}" >/dev/null
got=0; for _ in $(seq 1 30); do
  c=$(curl -s -X POST -H 'Content-Type: application/json' $PS/subscriptions/inventory-updated-dlq-sub:pull -d '{"maxMessages":10,"returnImmediately":true}' | grep -c ackId)
  [ "$c" -gt 0 ] && { got=1; break; }; sleep 5; done
check "ghost-order event reached inventory-updated-dlq after retries" "[ $got -eq 1 ]"

echo "--- 6. DLQ replay of a fixed message (orders-created)"
before=$(curl -s localhost:8082/api/v1/inventory/SKU-BLUE-2 | json 'd["available"]')
EV='{"eventId":"replay-'$RANDOM'","eventType":"order.created","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z","correlationId":"replay-1","source":"order-service","data":{"orderId":"ORD-REPLAY000001","customerId":"C","totalAmount":10,"currency":"USD","items":[{"sku":"SKU-BLUE-2","quantity":1,"unitPrice":10.0}]}}'
curl -s -X POST -H 'Content-Type: application/json' $PS/topics/orders-created-dlq:publish -d "{\"messages\":[{\"data\":\"$(printf '%s' "$EV" | base64 | tr -d '\n')\"}]}" >/dev/null
out=$(./scripts/dlq-replay.sh orders-created 10)
echo "   $out"
sleep 4
check "replayed message processed by inventory (stock $before -> $((before-1)))" "[ \$(curl -s localhost:8082/api/v1/inventory/SKU-BLUE-2 | json 'd[\"available\"]') -eq $((before-1)) ]"

echo; [ $fail -eq 0 ] && echo "PHASE 6 E2E: ALL PASSED" || echo "PHASE 6 E2E: FAILURES"
exit $fail
