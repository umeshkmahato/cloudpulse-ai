#!/usr/bin/env bash
# Starts/stops all four Java services for local demos (jars must be built). Logs and PIDs live in .run/.
# Usage: scripts/run-local.sh start|stop|status
set -uo pipefail
cd "$(dirname "$0")/.."
RUN=.run; mkdir -p $RUN
SERVICES="order-service:services/order-service:8081 inventory-service:services/inventory-service:8082 operations-service:services/operations-service:8083 analytics-sink:data-pipeline/analytics-sink:8084 ai-incident-assistant:services/ai-incident-assistant:8085"
case "${1:-}" in
  start)
    set -a; . ./.env; set +a; export PUBSUB_EMULATOR_HOST=localhost:8085
    for s in $SERVICES; do IFS=: read -r name dir port <<<"$s"
      if curl -sf localhost:$port/actuator/health >/dev/null; then echo "$name already up"; continue; fi
      nohup java -jar $dir/target/$name-0.1.0.jar > $RUN/$name.log 2>&1 & echo $! > $RUN/$name.pid
    done
    for s in $SERVICES; do IFS=: read -r name dir port <<<"$s"
      for _ in $(seq 1 60); do curl -sf localhost:$port/actuator/health >/dev/null && { echo "$name up (:$port)"; break; }; sleep 1; done
    done ;;
  stop)
    for s in $SERVICES; do IFS=: read -r name _ _ <<<"$s"; [ -f $RUN/$name.pid ] && kill "$(cat $RUN/$name.pid)" 2>/dev/null && echo "stopped $name"; rm -f $RUN/$name.pid; done ;;
  status)
    for s in $SERVICES; do IFS=: read -r name _ port <<<"$s"; curl -sf localhost:$port/actuator/health >/dev/null && echo "UP   $name :$port" || echo "DOWN $name :$port"; done ;;
  *) echo "usage: $0 start|stop|status"; exit 1 ;;
esac
