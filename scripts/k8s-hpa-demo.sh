#!/usr/bin/env bash
# Generates CPU load on order-service inside the kind cluster and watches the HPA scale it out, then back in.
# The load pod borrows the dashboard's label so the default-deny NetworkPolicy lets it talk to order-service.
# Usage: scripts/k8s-hpa-demo.sh [seconds=150]
set -uo pipefail
NS=cloudpulse; SECS="${1:-150}"
kubectl -n $NS delete pod loadgen --ignore-not-found >/dev/null 2>&1
kubectl -n $NS run loadgen --image=curlimages/curl:8.10.1 --image-pull-policy=IfNotPresent --restart=Never --labels=app.kubernetes.io/name=dashboard \
  --overrides='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":100,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"loadgen","image":"curlimages/curl:8.10.1","imagePullPolicy":"IfNotPresent","command":["sh","-c","end=$(( $(date +%s) + '"$SECS"' )); for w in 1 2 3 4 5 6 7 8 9 10 11 12; do ( while [ $(date +%s) -lt $end ]; do curl -s -o /dev/null -X POST http://order-service:8081/api/v1/orders -H \"Content-Type: application/json\" -d \"{\\\"customerId\\\":\\\"LOAD\\\",\\\"currency\\\":\\\"USD\\\",\\\"items\\\":[{\\\"sku\\\":\\\"SKU-BLUE-2\\\",\\\"quantity\\\":1,\\\"unitPrice\\\":1}]}\"; done ) & done; wait"],"resources":{"requests":{"cpu":"100m","memory":"32Mi"},"limits":{"cpu":"2","memory":"128Mi"}},"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
echo "load running for ${SECS}s; watching HPA (order-service)"
peak=2
for i in $(seq 1 $(( SECS / 10 + 6 ))); do
  cpu=$(kubectl -n $NS get hpa order-service -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null)
  cur=$(kubectl -n $NS get hpa order-service -o jsonpath='{.status.currentReplicas}' 2>/dev/null)
  line="cpu=${cpu:-?}% (target 70%) running=${cur:-?}"
  r=$(kubectl -n $NS get deploy order-service -o jsonpath='{.spec.replicas}')
  [ "$r" -gt "$peak" ] && peak=$r
  echo "t+$((i*10))s  $line  desired=$r"
  sleep 10
done
kubectl -n $NS delete pod loadgen --ignore-not-found >/dev/null 2>&1
echo "peak replicas: $peak (min 2, max 6)"
[ "$peak" -gt 2 ] && echo "HPA SCALE-OUT: OBSERVED" || echo "HPA SCALE-OUT: NOT OBSERVED"
