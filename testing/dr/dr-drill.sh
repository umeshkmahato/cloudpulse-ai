#!/usr/bin/env bash
# Disaster-recovery drills against the LOCAL kind cluster. Experimental POC targets: RTO 5 min, RPO 1 min (not commitments).
# A probe pod inside the cluster places an order and reads incidents every second through the authenticating gateway while a
# failure is injected; the harness measures user-visible outage, data loss, and catch-up time, then writes results.
#   testing/dr/dr-drill.sh zone-failure | db-crash | broker-outage | all
set -uo pipefail
cd "$(dirname "$0")/../.."
NS=cloudpulse; K="kubectl -n $NS"
RTO_TARGET=300; RPO_TARGET=60
RES=testing/dr/results; mkdir -p $RES
SCEN="${1:-all}"
PGPW=$($K get secret cloudpulse-db -o jsonpath='{.data.password}' | base64 -d)
pgq() { $K exec postgres-0 -- env PGPASSWORD=$PGPW psql -U cloudpulse -d cloudpulse -tAc "$1" 2>/dev/null; }
now() { docker exec cloudpulse-control-plane date +%s; }     # one clock for harness and probe pod (same VM)

ensure_stock() { pgq "insert into inventory.stock_items (sku, description, available, updated_at) values ('SKU-DR','Seed: DR drill stock',1000000,now()) on conflict (sku) do update set available=1000000, updated_at=now()" >/dev/null; }

start_probe() {
  local pw; pw=$(awk '$1=="operator"{print $2}' .run/identity-credentials.txt)
  $K delete pod dr-probe --ignore-not-found --wait=true >/dev/null 2>&1
  $K delete secret dr-probe --ignore-not-found >/dev/null 2>&1
  $K create secret generic dr-probe --from-literal=pass="$pw" >/dev/null
  cat <<YAML | $K apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: dr-probe
  labels: { purpose: dr-probe }          # an external-client stand-in; NetworkPolicy allows it to reach only the gateway
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  nodeSelector: { node-role.kubernetes.io/control-plane: "" }         # survives the worker failures we inject
  tolerations: [ { key: node-role.kubernetes.io/control-plane, operator: Exists, effect: NoSchedule } ]
  securityContext: { runAsNonRoot: true, runAsUser: 100, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: probe
      image: curlimages/curl:8.10.1
      imagePullPolicy: IfNotPresent
      env: [ { name: OP_PASS, valueFrom: { secretKeyRef: { name: dr-probe, key: pass } } } ]
      resources: { requests: { cpu: 20m, memory: 32Mi }, limits: { memory: 128Mi } }
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
      command:
        - sh
        - -c
        - |
          GW=http://dashboard:8080
          until curl -s -m 4 -c /tmp/j -o /dev/null -w '%{http_code}' -X POST \$GW/auth/login -H 'Content-Type: application/json' -d "{\"username\":\"operator\",\"password\":\"\$OP_PASS\"}" | grep -q 200; do sleep 2; done
          echo "READY \$(date +%s)"
          while true; do
            t=\$(date +%s)
            wr=\$(curl -s -m 4 -b /tmp/j -o /tmp/o -w '%{http_code} %{time_total}' -X POST \$GW/orders-api/api/v1/orders -H 'Content-Type: application/json' -H 'X-CloudPulse-CSRF: 1' -d '{"customerId":"DR-PROBE","currency":"USD","items":[{"sku":"SKU-DR","quantity":1,"unitPrice":1}]}')
            id=\$(sed -n 's/.*"orderId":"\([^"]*\)".*/\1/p' /tmp/o)
            echo "W \$t \$wr \$id"
            rr=\$(curl -s -m 4 -b /tmp/j -o /dev/null -w '%{http_code} %{time_total}' "\$GW/ops-api/api/v1/incidents?limit=1")
            echo "R \$t \$rr"
            sleep 1
          done
YAML
  for _ in $(seq 1 60); do $K logs dr-probe 2>/dev/null | grep -q "^W .* 201" && return 0; sleep 2; done
  echo "probe never became healthy"; return 1
}
stop_probe() { $K logs dr-probe > "$1" 2>/dev/null; $K delete pod dr-probe --ignore-not-found --wait=false >/dev/null 2>&1; $K delete secret dr-probe --ignore-not-found >/dev/null 2>&1; }

wait_pending_zero() { # seconds budget -> prints seconds waited
  local s=$(now) i; for i in $(seq 1 "$1"); do [ "$(pgq "select count(*) from orders.orders where customer_id='DR-PROBE' and status='PENDING' and created_at < now() - interval '3 seconds'")" = 0 ] && { echo $(( $(now)-s )); return; }; sleep 1; done; echo -1
}

# analyse <log> <t_inject> <t_end_of_fault> <scenario> <extra-json> -> appends a result object to $RES/<scenario>.json
analyse() {
  local log="$1" t0="$2" t1="$3" scen="$4" extra="$5"
  pgq "select order_id from orders.orders where customer_id='DR-PROBE'" > $RES/.db-ids.txt
  python3 - "$log" "$t0" "$t1" "$scen" "$extra" "$RES/.db-ids.txt" "$RTO_TARGET" "$RPO_TARGET" <<'PY'
import sys, json
log, t0, t1, scen, extra, ids_file, rto_t, rpo_t = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], json.loads(sys.argv[5]), sys.argv[6], int(sys.argv[7]), int(sys.argv[8])
W, R, acked = [], [], []
for l in open(log):
    p = l.split()
    if len(p) >= 4 and p[0] in "WR":
        lat = float(p[3]) if p[3].replace(".", "", 1).isdigit() else 0.0
        (W if p[0] == "W" else R).append((int(p[1]), p[2], lat))
        if p[0] == "W" and p[2] == "201" and len(p) > 4: acked.append(p[4])
def worst_latency(series):
    return round(max([l for t, _, l in series if t >= t0] or [0]), 2)
def outage(series, ok):
    win = [(t, c) for t, c, _ in series if t >= t0]
    bad = [t for t, c in win if not ok(c)]
    if not bad: return 0, 0, 100.0
    first, last = min(bad), max(bad)
    after = [t for t, c in win if t > last and ok(c)]
    rec = min(after) if after else None
    inwin = [(t, c) for t, c in win if t0 <= t <= max(t1, last)]
    succ = sum(1 for _, c in inwin if ok(c)) / max(len(inwin), 1)
    return ((rec - first) if rec else None), len(bad), round(100 * succ, 1)
w_out, w_bad, w_av = outage(W, lambda c: c == "201")
r_out, r_bad, r_av = outage(R, lambda c: c == "200")
db = set(open(ids_file).read().split())
lost = [i for i in acked if i not in db]
res = dict(scenario=scen, target_rto_s=rto_t, target_rpo_s=rpo_t, write_outage_s=w_out, write_failed_requests=w_bad, write_availability_pct_during_fault=w_av,
           write_worst_latency_s=worst_latency(W), read_worst_latency_s=worst_latency(R), read_outage_s=r_out, read_failed_requests=r_bad, read_availability_pct_during_fault=r_av, orders_acknowledged=len(acked), acknowledged_orders_lost=len(lost), **extra)
res["rto_met"] = (w_out is not None and w_out <= rto_t)
res["rpo_met"] = (len(lost) == 0)
json.dump(res, open(f"{sys.argv[1].rsplit('/',1)[0]}/{scen}.json", "w"), indent=2)
print(json.dumps(res, indent=2))
PY
}

wait_all_ready() { for d in order-service inventory-service operations-service analytics-sink ai-incident-assistant remediation-controller identity-service dashboard; do $K rollout status deploy/$d --timeout=240s >/dev/null 2>&1; done; }

# ------------------------------------------------------------------ scenario 1: zone/node failure
# Kubernetes never rebalances running pods, so after any earlier node failure everything may sit in one zone. Re-spread first.
rebalance() {
  echo "   re-spreading workloads across both zones (rolling restart)"
  for d in order-service inventory-service operations-service analytics-sink ai-incident-assistant remediation-controller identity-service dashboard; do $K rollout restart deploy/$d >/dev/null; done
  wait_all_ready
  local n; n=$($K get pods -l 'app.kubernetes.io/name in (order-service,identity-service,dashboard,inventory-service)' -o jsonpath='{range .items[*]}{.spec.nodeName}{"\n"}{end}' | sort -u | wc -l | tr -d ' ')
  echo "   order-path pods now run on $n worker nodes"
}

zone_failure() {
  echo "=== SCENARIO: loss of a whole zone (worker node cloudpulse-worker2 frozen; pods on it become unreachable)"
  rebalance
  ensure_stock; start_probe || return 1; sleep 8
  local on_node; on_node=$($K get pods -o wide --no-headers | awk '$7=="cloudpulse-worker2"{print $1}' | sed 's/-[a-z0-9]*-[a-z0-9]*$//' | sort | uniq | tr '\n' ' ')
  local t0; t0=$(now); echo "   workloads on the failed zone: $on_node"
  docker pause cloudpulse-worker2 >/dev/null; echo "   [t0] zone failed at $t0"
  local detect=-1 cap=-1
  for _ in $(seq 1 60); do                                              # 1) how long until Kubernetes even notices the node is gone
    sleep 2; kubectl get node cloudpulse-worker2 --no-headers 2>/dev/null | grep -q NotReady && { detect=$(( $(now)-t0 )); break; }
  done
  echo "   control plane marked the node NotReady after ${detect}s"
  for _ in $(seq 1 120); do                                             # 2) until every app deployment is back to its desired READY replicas on the surviving zone
    sleep 2
    if $K get deploy -o json | python3 -c "import sys,json;d=[x for x in json.load(sys.stdin)['items'] if x['metadata']['labels'].get('app.kubernetes.io/part-of')=='cloudpulse'];sys.exit(0 if all(x['status'].get('readyReplicas',0)>=x['spec']['replicas'] for x in d) else 1)" 2>/dev/null; then cap=$(( $(now)-t0 )); break; fi
  done
  if [ "$cap" = -1 ]; then echo "   capacity NOT fully restored; state:"; $K get deploy --no-headers | awk '{print "     "$1,$2}'; $K get pods --no-headers | grep -vE "Running|Completed" | awk '{print "     pod "$1,$3}'; $K get events --field-selector type=Warning --sort-by=.lastTimestamp 2>/dev/null | tail -4 | cut -c1-200; fi
  echo "   full desired capacity restored on the surviving zone after ${cap}s"
  sleep 20; local t1; t1=$(now)
  docker unpause cloudpulse-worker2 >/dev/null; echo "   zone restored"
  for _ in $(seq 1 60); do kubectl get node cloudpulse-worker2 --no-headers 2>/dev/null | grep -q " Ready" && break; sleep 3; done
  sleep 30
  stop_probe $RES/zone-failure.log
  local catch; catch=$(wait_pending_zero 180)
  analyse $RES/zone-failure.log "$t0" "$t1" zone-failure "{\"injected\":\"docker pause of one of two worker nodes (zone-b)\",\"node_notready_detected_s\":$detect,\"capacity_restored_s\":$cap,\"pending_order_catchup_s\":$catch}"
}

# ------------------------------------------------------------------ scenario 2: database crash
db_crash() {
  echo "=== SCENARIO: PostgreSQL pod killed (force delete, no graceful shutdown)"
  ensure_stock; start_probe || return 1; sleep 8
  local old_uid; old_uid=$($K get pod postgres-0 -o jsonpath='{.metadata.uid}')
  local t0; t0=$(now); $K delete pod postgres-0 --grace-period=0 --force >/dev/null 2>&1; echo "   [t0] database killed at $t0"
  local ready=-1                                                         # wait for the NEW pod (different uid), not the one being deleted
  for _ in $(seq 1 120); do
    uid=$($K get pod postgres-0 -o jsonpath='{.metadata.uid}' 2>/dev/null)
    if [ -n "$uid" ] && [ "$uid" != "$old_uid" ] && [ "$($K get pod postgres-0 -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null)" = true ]; then ready=$(( $(now)-t0 )); break; fi
    sleep 1
  done
  echo "   postgres replacement Ready after ${ready}s"
  sleep 45; local t1; t1=$(now)
  stop_probe $RES/db-crash.log
  local catch; catch=$(wait_pending_zero 240)
  local arch; arch=$(pgq "select failed_count from pg_stat_archiver")
  analyse $RES/db-crash.log "$t0" "$t1" db-crash "{\"injected\":\"kubectl delete pod postgres-0 --force --grace-period=0 (crash recovery from the persistent volume)\",\"postgres_ready_s\":$ready,\"pending_order_catchup_s\":$catch,\"wal_archive_failures\":${arch:-null}}"
}

# ------------------------------------------------------------------ scenario 3: message broker outage
broker_outage() {
  echo "=== SCENARIO: Pub/Sub (emulator) unavailable for 60 s"
  ensure_stock; start_probe || return 1; sleep 8
  local t0; t0=$(now); $K scale deploy/pubsub-emulator --replicas=0 >/dev/null; echo "   [t0] broker removed at $t0"
  sleep 60; local t1; t1=$(now)
  local pending_outbox; pending_outbox=$(pgq "select count(*) from orders.outbox_events where published_at is null")
  echo "   events waiting in the order outbox during the outage: $pending_outbox"
  $K scale deploy/pubsub-emulator --replicas=1 >/dev/null; $K rollout status deploy/pubsub-emulator --timeout=240s >/dev/null 2>&1
  # EMULATOR ONLY: it is in-memory, so topics/subscriptions are gone. Re-create them and let consumers re-subscribe. Managed Pub/Sub keeps them.
  $K delete job pubsub-init --ignore-not-found >/dev/null; kubectl apply -k kubernetes/overlays/local >/dev/null 2>&1
  $K wait --for=condition=complete job/pubsub-init --timeout=240s >/dev/null 2>&1
  local t2; t2=$(now)
  for d in inventory-service operations-service analytics-sink; do $K rollout restart deploy/$d >/dev/null; done
  for d in inventory-service operations-service analytics-sink; do $K rollout status deploy/$d --timeout=240s >/dev/null 2>&1; done
  $K rollout restart deploy/order-service >/dev/null; $K rollout status deploy/order-service --timeout=240s >/dev/null 2>&1
  sleep 20
  stop_probe $RES/broker-outage.log
  local catch; catch=$(wait_pending_zero 240)
  local still; still=$(pgq "select count(*) from orders.outbox_events where published_at is null")
  analyse $RES/broker-outage.log "$t0" "$t1" broker-outage "{\"injected\":\"pubsub-emulator scaled to 0 for 60 s, then restored (+ topic re-creation, emulator-only)\",\"outbox_events_waiting_at_peak\":${pending_outbox:-null},\"outbox_events_still_unpublished\":${still:-null},\"pending_order_catchup_s\":$catch,\"broker_back_to_catchup_s\":$(( $(now)-t2 ))}"
}

case "$SCEN" in
  zone-failure) zone_failure ;;
  db-crash) db_crash ;;
  broker-outage) broker_outage ;;
  all) zone_failure; db_crash; broker_outage ;;
  *) echo "usage: $0 zone-failure|db-crash|broker-outage|all"; exit 1 ;;
esac
