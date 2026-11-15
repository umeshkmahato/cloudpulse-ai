#!/usr/bin/env bash
# Phase 3 smoke test: verifies local infra and a Pub/Sub publish -> pull -> ack round trip.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; [ -f .env ] && . ./.env; set +a
PROJECT="${GCP_PROJECT_ID:-cloudpulse-ai-poc}"
PS="http://localhost:8085/v1/projects/$PROJECT"
fail=0
check() { if eval "$2" >/dev/null 2>&1; then echo "[PASS] $1"; else echo "[FAIL] $1"; fail=1; fi; }

check "postgres accepts connections"  "docker compose exec -T postgres pg_isready -U ${POSTGRES_USER:-cloudpulse}"
check "postgres schemas created"       "docker compose exec -T postgres psql -U ${POSTGRES_USER:-cloudpulse} -d ${POSTGRES_DB:-cloudpulse} -tAc \"select 1 from information_schema.schemata where schema_name='orders'\" | grep -q 1"
check "valkey PING"                    "docker compose exec -T valkey valkey-cli ping | grep -q PONG"
check "prometheus healthy"             "curl -sf http://localhost:9090/-/healthy"
check "grafana healthy"                "curl -sf http://localhost:3000/api/health"
topics=$(curl -s "$PS/topics" | grep -o '"name"' | wc -l | tr -d ' ')
check "14 topics (7 + 7 DLQ), found $topics" "[ $topics -eq 14 ]"

# Round trip
MSG=$(printf '{"eventId":"smoke-%s","type":"smoke"}' "$RANDOM" | base64)
curl -s -X POST -H 'Content-Type: application/json' "$PS/topics/orders-created:publish" \
  -d "{\"messages\":[{\"data\":\"$MSG\",\"attributes\":{\"correlationId\":\"smoke\"}}]}" >/dev/null
PULL=$(curl -s -X POST -H 'Content-Type: application/json' "$PS/subscriptions/orders-created-inventory-sub:pull" -d '{"maxMessages":1}')
ACK=$(echo "$PULL" | grep -o '"ackId": *"[^"]*"' | head -1 | sed 's/.*: *"//;s/"$//')
check "publish -> pull round trip" "[ -n '$ACK' ]"
[ -n "$ACK" ] && curl -s -X POST -H 'Content-Type: application/json' "$PS/subscriptions/orders-created-inventory-sub:acknowledge" -d "{\"ackIds\":[\"$ACK\"]}" >/dev/null
exit $fail
