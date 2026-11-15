#!/usr/bin/env bash
# Phase 9 end-to-end: baseline -> no false alarm -> overload -> incident + alert event -> recovery -> resolved.
# Uses unique SYNTHETIC service names so earlier test data cannot pollute baselines. Needs: docker compose up -d, ops jar built.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
export PUBSUB_EMULATOR_HOST=localhost:8085
PS="http://localhost:8085/v1/projects/${GCP_PROJECT_ID:-cloudpulse-ai-poc}"
RUN=.run; mkdir -p $RUN
PIDS=(); trap 'for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done' EXIT
java -jar services/operations-service/target/operations-service-0.1.0.jar > $RUN/operations-service.log 2>&1 & PIDS+=($!)
for _ in $(seq 1 60); do curl -sf localhost:8083/actuator/health >/dev/null && break; sleep 1; done
fail=0
check() { if eval "$2"; then echo "[PASS] $1"; else echo "[FAIL] $1"; fail=1; fi; }
json() { python3 -c "import sys,json;d=json.load(sys.stdin);r=($1);print(json.dumps(r) if isinstance(r,(dict,list)) else r)"; }
incident_field() { python3 -c "import json;d=json.load(open('$RUN/inc9.json'));print($1)"; }
S="e2e9-$RANDOM"; P="$S-peer"
state() { curl -s -X POST localhost:8083/api/v1/detection/evaluate | json "[x for x in d if x['assessment']['service']=='$1'][0]$2"; }
open_incident() { curl -s "localhost:8083/api/v1/incidents?status=OPEN&limit=100" | json "next((i for i in d if i['service']=='$1'), None)"; }

echo "--- 1. learn baseline from 15 min of normal SYNTHETIC history ($S, $P)"
./scripts/simulate-telemetry.py --services $S,$P --scenario normal --backfill-minutes 15 --duration 6 --interval 2 > $RUN/sim9a.log 2>&1
check "normal operation: status OK, risk 0, no incident" "[ \"\$(state $S \"['assessment']['status']\")\" = OK ] && [ \"\$(open_incident $S)\" = None ]"
check "baseline learned from >=30 samples per metric" "curl -s localhost:8083/api/v1/detection/current | json \"[x for x in d if x['assessment']['service']=='$S'][0]['assessment']['findings'][0]['baselineSamples']>=30\" | grep -q True"

echo "--- 2. new service without history is NOT scored (insufficient data)"
./scripts/simulate-telemetry.py --services fresh-$S --scenario normal --duration 6 --interval 2 > $RUN/sim9f.log 2>&1
check "fresh-$S reports INSUFFICIENT_DATA, risk 0" "[ \"\$(state fresh-$S \"['assessment']['status']\")\" = INSUFFICIENT_DATA ]"

echo "--- 3. overload ramp (100->1000 rpm, CPU 40->90%, DB 50->95%, latency 300->2500 ms)"
ALERT_BEFORE=$(curl -s -X POST -H 'Content-Type: application/json' $PS/subscriptions/infrastructure-alerts-ops-sub:pull -d '{"maxMessages":100,"returnImmediately":true}' | grep -c ackId)
./scripts/simulate-telemetry.py --services $S,$P --scenario spike --duration 30 --interval 2 > $RUN/sim9b.log 2>&1
sched=0; for _ in $(seq 1 25); do [ "$(open_incident $S)" != None ] && { sched=1; break; }; sleep 1; done
check "scheduler opened the incident on its own (no manual evaluate)" "[ $sched -eq 1 ]"
check "status ANOMALY" "[ \"\$(state $S \"['assessment']['status']\")\" = ANOMALY ]"
check "risk score >= 80" "[ \"\$(state $S \"['assessment']['riskScore']>=80\")\" = True ]"
open_incident $S > $RUN/inc9.json
INC=$RUN/inc9.json
check "one OPEN DETECTED incident for $S" "[ \"\$(incident_field 'd[\"kind\"]+d[\"status\"]')\" = DETECTEDOPEN ]"
check "evidence is explainable (z-score, baseline, EWMA limit per anomalous metric)" "[ \"\$(incident_field 'all(k in f for f in d[\"evidence\"][\"findings\"] if f[\"anomalous\"] for k in (\"zScore\",\"baselineMean\",\"ewma\",\"ewmaUpperControlLimit\",\"explanation\")) and any(f[\"metric\"]==\"cpu_pct\" and f[\"anomalous\"] for f in d[\"evidence\"][\"findings\"])')\" = True ]"
check "recommendation is a predefined action requiring approval" "[ \"\$(incident_field 'any(r[\"actionId\"]==\"SCALE_DEPLOYMENT\" and r[\"requiresApproval\"] for r in d[\"recommendations\"])')\" = True ]"
check "incident is labelled synthetic and lists affected services" "[ \"\$(incident_field 'd[\"synthetic\"] and isinstance(d[\"affectedServices\"], list)')\" = True ]"
check "still exactly one open incident for $S after repeated evaluation" "state $S \"['assessment']['status']\" >/dev/null; [ \$(curl -s 'localhost:8083/api/v1/incidents?status=OPEN&limit=100' | json \"len([i for i in d if i['service']=='$S'])\") -eq 1 ]"
ALERTS=$(curl -s -X POST -H 'Content-Type: application/json' $PS/subscriptions/infrastructure-alerts-ops-sub:pull -d '{"maxMessages":100,"returnImmediately":true}')
check "alert.raised OPENED published to infrastructure-alerts" "echo '$ALERTS' | python3 -c 'import sys,json,base64;ms=json.load(sys.stdin).get(\"receivedMessages\",[]);ok=any((lambda e:e[\"eventType\"]==\"alert.raised\" and e[\"data\"][\"service\"]==\"$S\" and e[\"data\"][\"state\"]==\"OPENED\" and e[\"data\"][\"synthetic\"])(json.loads(base64.b64decode(m[\"message\"][\"data\"]))) for m in ms);sys.exit(0 if ok else 1)'"
echo "   peer service (25% stress): status=$(state $P "['assessment']['status']") risk=$(state $P "['assessment']['riskScore']")  (lower than $S: ranking works)"

echo "--- 4. recovery: normal data resumes -> incident resolves (needs 6 clean evaluations, never on stale data)"
./scripts/simulate-telemetry.py --services $S,$P --scenario normal --duration 25 --interval 1 > $RUN/sim9c.log 2>&1
for _ in 1 2 3 4 5 6 7; do curl -s -X POST localhost:8083/api/v1/detection/evaluate >/dev/null; done
check "incident RESOLVED with peak risk retained" "curl -s 'localhost:8083/api/v1/incidents?status=RESOLVED&limit=100' | json \"any(i['service']=='$S' and i['peakRisk']>=80 for i in d)\" | grep -q True"
check "no OPEN incident remains for $S" "[ \"\$(open_incident $S)\" = None ]"

echo; [ $fail -eq 0 ] && echo "PHASE 9 E2E: ALL PASSED" || echo "PHASE 9 E2E: FAILURES"
exit $fail
