#!/usr/bin/env bash
# Phase 7 end-to-end: Pub/Sub -> analytics-sink -> analytics store -> Operations analytics APIs.
# Prereqs: docker compose up -d; all jars packaged.  Usage: scripts/e2e-phase7.sh
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
export PUBSUB_EMULATOR_HOST=localhost:8085
PS="http://localhost:8085/v1/projects/${GCP_PROJECT_ID:-cloudpulse-ai-poc}"
RUN=.run; mkdir -p $RUN
PIDS=()
trap 'for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done' EXIT
startjar() { java -jar $1 > $RUN/$2.log 2>&1 & PIDS+=($!)
  for _ in $(seq 1 60); do curl -sf localhost:$3/actuator/health >/dev/null && return 0; sleep 1; done
  echo "FAILED to start $2 (see $RUN/$2.log)"; exit 1; }
fail=0
check() { if eval "$2"; then echo "[PASS] $1"; else echo "[FAIL] $1"; fail=1; fi; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
sql() { docker compose exec -T postgres psql -U $POSTGRES_USER -d $POSTGRES_DB -tAc "$1"; }
order() { curl -s -X POST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d "{\"customerId\":\"CUST-E2E7\",\"currency\":\"USD\",\"items\":[{\"sku\":\"$1\",\"quantity\":$2,\"unitPrice\":10.00}]}" | json 'd["orderId"]'; }

startjar services/order-service/target/order-service-0.1.0.jar order-service 8081
startjar services/inventory-service/target/inventory-service-0.1.0.jar inventory-service 8082
startjar services/operations-service/target/operations-service-0.1.0.jar operations-service 8083
startjar data-pipeline/analytics-sink/target/analytics-sink-0.1.0.jar analytics-sink 8084

o_before=$(sql "select count(*) from analytics.order_events"); i_before=$(sql "select count(*) from analytics.inventory_events")
echo "--- 1. business events flow to the analytics store"
A=$(order SKU-RED-1 1); B=$(order SKU-BLUE-2 1); C=$(order SKU-LOW-3 99)
sleep 6
check "3 order_events landed ($o_before -> $(sql 'select count(*) from analytics.order_events'))" "[ $(sql 'select count(*) from analytics.order_events') -eq $((o_before+3)) ]"
check "3 inventory_events landed" "[ $(sql 'select count(*) from analytics.inventory_events') -eq $((i_before+3)) ]"
check "correlation preserved order->inventory for $C" "[ \$(sql \"select count(distinct correlation_id) from (select correlation_id from analytics.order_events where order_id='$C' union all select correlation_id from analytics.inventory_events where order_id='$C') x\") -eq 1 ]"

echo "--- 2. sink is idempotent (re-publish an already stored event)"
payload=$(sql "select payload from orders.outbox_events where payload like '%$A%' limit 1")
curl -s -X POST -H 'Content-Type: application/json' $PS/topics/orders-created:publish -d "{\"messages\":[{\"data\":\"$(printf '%s' "$payload" | base64 | tr -d '\n')\"}]}" >/dev/null
sleep 4
check "no extra row after duplicate delivery" "[ $(sql 'select count(*) from analytics.order_events') -eq $((o_before+3)) ]"
check "sink duplicate counter > 0" "curl -s localhost:8084/actuator/prometheus | grep -E '^sink_events_duplicates_total' | grep -vq ' 0.0'"

echo "--- 3. synthetic telemetry -> analytics store"
./scripts/simulate-telemetry.py --scenario normal --duration 6 --interval 2 > $RUN/sim.log 2>&1
sleep 4
check "metric_samples stored, all labelled synthetic" "[ \$(sql \"select count(*) from analytics.metric_samples where synthetic=false\") -eq 0 ] && [ \$(sql 'select count(*) from analytics.metric_samples') -ge 48 ]"

echo "--- 4. Operations analytics APIs"
check "overview shows >=3 orders and an inventory outcome breakdown" "curl -s 'localhost:8083/api/v1/analytics/overview?minutes=60' | json 'd[\"orders\"][\"orders\"]>=3 and len(d[\"inventoryOutcomes\"])>=1' | grep -q True"
check "outcomes include INSUFFICIENT_STOCK reason" "curl -s 'localhost:8083/api/v1/analytics/inventory/outcomes' | grep -q INSUFFICIENT_STOCK:SKU-LOW-3"
check "order trend returns buckets" "curl -s 'localhost:8083/api/v1/analytics/orders/trend?minutes=60&bucketSeconds=60' | json 'len(d)>=1' | grep -q True"
check "metric trend for cpu_pct is synthetic-labelled" "curl -s 'localhost:8083/api/v1/analytics/metrics/trend?service=order-service&metric=cpu_pct&minutes=60&bucketSeconds=10' | json 'len(d)>=1 and all(r[\"synthetic\"] for r in d)' | grep -q True"
check "pipeline latency p95 reported for all 3 event types" "curl -s 'localhost:8083/api/v1/analytics/pipeline/latency' | json 'sorted(r[\"type\"] for r in d)==[\"inventory.updated\",\"metric.sample\",\"order.created\"]' | grep -q True"
check "recent feed is business events only by default" "curl -s 'localhost:8083/api/v1/analytics/events/recent?limit=50' | json 'len(d)>=3 and {e[\"type\"] for e in d}=={\"order.created\",\"inventory.updated\"}' | grep -q True"
check "recent feed with includeMetrics shows all 3 types" "curl -s 'localhost:8083/api/v1/analytics/events/recent?limit=200&includeMetrics=true' | json 'len({e[\"type\"] for e in d})==3' | grep -q True"
check "invalid parameter rejected (400)" "[ \$(curl -s -o /dev/null -w '%{http_code}' 'localhost:8083/api/v1/analytics/overview?minutes=0') = 400 ]"
echo "   pipeline latency (ms) - inventory.updated p50 is inflated on the first run: the sink drained a backlog of older events that accumulated before it existed:"; curl -s 'localhost:8083/api/v1/analytics/pipeline/latency' | json '"\n".join("     %s events=%s p50=%.0f p95=%.0f"%(r["type"],r["events"],r["p50_ms"],r["p95_ms"]) for r in d)'

echo; [ $fail -eq 0 ] && echo "PHASE 7 E2E: ALL PASSED" || echo "PHASE 7 E2E: FAILURES"
exit $fail
