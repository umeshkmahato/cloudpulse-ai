#!/usr/bin/env bash
# Backup/restore drill (local): base backup + continuous WAL archive -> restore into a scratch Postgres -> measure RPO and RTO.
# Simulates "the database volume is gone, only the backup volume survived", at three moments inside the 30 s archive interval.
# The archive is FROZEN at each disaster instant, so segments archived afterwards cannot flatter the result.
# Experimental targets: RTO 5 min, RPO 1 min. The backup volume lives in the same cluster: this rehearses the PROCEDURE; real DR needs another region.
set -uo pipefail
cd "$(dirname "$0")/../.."
NS=cloudpulse; K="kubectl -n $NS"; RES=testing/dr/results; mkdir -p $RES
PGPW=$($K get secret cloudpulse-db -o jsonpath='{.data.password}' | base64 -d)
pgq() { $K exec postgres-0 -- env PGPASSWORD=$PGPW psql -U cloudpulse -d cloudpulse -tAc "$1" 2>/dev/null; }
now() { docker exec cloudpulse-control-plane date +%s; }

echo "=== 1. base backup (pg_basebackup over the local socket; tar+gzip, WAL comes from the archive)"
B=$(now)
$K exec postgres-0 -- sh -c "rm -rf /backup/base && mkdir -p /backup/base/$B && pg_basebackup -U cloudpulse -D /backup/base/$B -Ft -z -X none -c fast" 2>&1 | tail -2
BACKUP_S=$(( $(now)-B )); echo "   base backup taken in ${BACKUP_S}s: $($K exec postgres-0 -- sh -c "du -sh /backup/base/$B | cut -f1") (backup id $B)"

echo "=== 2. steady write load: one marker row per second plus real order traffic via SQL"
pgq "create schema if not exists dr; create table if not exists dr.marker (id bigserial primary key, at timestamptz not null default now()); truncate dr.marker" >/dev/null
( while true; do pgq "insert into dr.marker default values" >/dev/null; sleep 1; done ) & LOAD=$!
trap 'kill $LOAD 2>/dev/null; $K delete pod pg-restore --ignore-not-found --wait=false >/dev/null 2>&1' EXIT
sleep 25

restore_at() { # n
  local n=$1
  $K exec postgres-0 -- sh -c "rm -rf /backup/wal-frozen-$n && cp -a /backup/wal /backup/wal-frozen-$n"        # the archive as it exists RIGHT NOW
  local F; F=$(now)
  local prod_last_id prod_last_at prod_orders
  prod_last_id=$(pgq "select max(id) from dr.marker"); prod_last_at=$(pgq "select extract(epoch from max(at))::bigint from dr.marker")
  prod_orders=$(pgq "select count(*) from orders.orders")
  echo "--- disaster #$n at $F: production had marker $prod_last_id and $prod_orders orders"
  $K delete pod pg-restore --ignore-not-found --wait=true >/dev/null 2>&1
  cat <<YAML | $K apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata: { name: pg-restore, labels: { purpose: dr-restore } }
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: { runAsNonRoot: true, runAsUser: 70, runAsGroup: 70, fsGroup: 70, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: restore
      image: postgres:16-alpine
      imagePullPolicy: IfNotPresent
      resources: { requests: { cpu: 100m, memory: 128Mi }, limits: { memory: 512Mi } }
      securityContext: { allowPrivilegeEscalation: false, capabilities: { drop: ["ALL"] } }
      volumeMounts: [ { name: backup, mountPath: /backup, readOnly: true }, { name: scratch, mountPath: /scratch } ]
      command:
        - sh
        - -c
        - |
          set -e
          trap 'echo "--- postgres log:"; tail -25 /scratch/pg.log 2>/dev/null' EXIT
          S=\$(date +%s)
          mkdir -p /scratch/data && chmod 700 /scratch/data
          B=\$(ls -d /backup/base/*/ | head -1)
          tar -xzf \${B}base.tar.gz -C /scratch/data
          [ -f \${B}pg_wal.tar.gz ] && tar -xzf \${B}pg_wal.tar.gz -C /scratch/data/pg_wal || true
          touch /scratch/data/recovery.signal
          # replay from the FROZEN archive; archiving is switched off so this scratch instance can never write into the real archive
          postgres -D /scratch/data -p 5433 -c listen_addresses=localhost -c archive_mode=off -c max_connections=200 -c restore_command='cp /backup/wal-frozen-$n/%f %p' -c recovery_target_timeline=latest > /scratch/pg.log 2>&1 &
          for i in \$(seq 1 120); do
            if pg_isready -p 5433 -q && [ "\$(psql -p 5433 -U cloudpulse -d cloudpulse -tAc 'select pg_is_in_recovery()' 2>/dev/null)" = f ]; then break; fi
            sleep 1
          done
          E=\$(date +%s)
          LAST_ID=\$(psql -p 5433 -U cloudpulse -d cloudpulse -tAc 'select coalesce(max(id),0) from dr.marker')
          LAST_AT=\$(psql -p 5433 -U cloudpulse -d cloudpulse -tAc "select coalesce(extract(epoch from max(at))::bigint,0) from dr.marker")
          ORDERS=\$(psql -p 5433 -U cloudpulse -d cloudpulse -tAc 'select count(*) from orders.orders')
          echo "RESULT restore_s=\$((E-S)) last_id=\$LAST_ID last_at=\$LAST_AT orders=\$ORDERS"
          sleep 2
      
  volumes:
    - { name: backup, persistentVolumeClaim: { claimName: pg-backup } }
    - { name: scratch, emptyDir: {} }
YAML
  for _ in $(seq 1 180); do $K logs pg-restore 2>/dev/null | grep -q "^RESULT" && break; sleep 1; done
  local line; line=$($K logs pg-restore 2>/dev/null | grep "^RESULT" | tail -1)
  if [ -z "$line" ]; then echo "   RESTORE FAILED:"; $K logs pg-restore 2>&1 | tail -8; return 1; fi
  local rs last_id last_at orders
  rs=$(echo "$line" | sed 's/.*restore_s=\([0-9]*\).*/\1/'); last_id=$(echo "$line" | sed 's/.*last_id=\([0-9]*\).*/\1/')
  last_at=$(echo "$line" | sed 's/.*last_at=\([0-9]*\).*/\1/'); orders=$(echo "$line" | sed 's/.*orders=\([0-9]*\).*/\1/')
  local lost=$(( prod_last_id - last_id )); local rpo=$(( prod_last_at - last_at ))
  echo "   restored in ${rs}s; recovered marker $last_id (lost $lost rows, data-loss window ${rpo}s); orders $orders of $prod_orders"
  RESULTS+=("{\"disaster\":$n,\"restore_s\":$rs,\"production_last_marker\":$prod_last_id,\"restored_last_marker\":$last_id,\"rows_lost\":$lost,\"data_loss_window_s\":$rpo,\"orders_in_production\":$prod_orders,\"orders_restored\":$orders}")
  $K delete pod pg-restore --ignore-not-found --wait=false >/dev/null 2>&1
}

RESULTS=()
restore_at 1
[ "${PITR_RUNS:-3}" -ge 2 ] && { sleep 11; restore_at 2; sleep 11; restore_at 3; }
kill $LOAD 2>/dev/null

python3 - "$BACKUP_S" "${RESULTS[@]}" <<'PY'
import sys, json
backup_s = int(sys.argv[1]); runs = [json.loads(x) for x in sys.argv[2:]]
if not runs: sys.exit("no successful restore: nothing to report")
out = dict(scenario="pitr-restore", injected="production volume 'lost'; restore from base backup + frozen WAL archive into a scratch Postgres (3 disaster instants)",
           target_rto_s=300, target_rpo_s=60, base_backup_s=backup_s, archive_timeout_s=30, runs=runs,
           worst_restore_s=max(r["restore_s"] for r in runs), worst_data_loss_window_s=max(r["data_loss_window_s"] for r in runs),
           worst_rows_lost=max(r["rows_lost"] for r in runs))
out["rto_met"] = out["worst_restore_s"] <= 300
out["rpo_met"] = out["worst_data_loss_window_s"] <= 60
json.dump(out, open("testing/dr/results/pitr-restore.json", "w"), indent=2)
print(json.dumps(out, indent=2))
PY
