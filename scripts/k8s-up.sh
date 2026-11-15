#!/usr/bin/env bash
# Builds images, creates a local kind cluster (3 nodes, 2 fake zones) and deploys CloudPulse with the `local` overlay.
# Everything is local and free. GKE is NOT touched. Usage: scripts/k8s-up.sh [--skip-build]
set -euo pipefail
cd "$(dirname "$0")/.."
SKIP_BUILD="${1:-}"
CLUSTER=cloudpulse
IMAGES=(order-service:services/order-service inventory-service:services/inventory-service operations-service:services/operations-service
        analytics-sink:data-pipeline/analytics-sink ai-incident-assistant:services/ai-incident-assistant remediation-controller:services/remediation-controller identity-service:services/identity-service dashboard:frontend/operations-dashboard)

[ -f .env ] || { echo "missing .env (cp .env.example .env)"; exit 1; }
set -a; . ./.env; set +a

if [ "$SKIP_BUILD" != "--skip-build" ]; then
  for i in "${IMAGES[@]}"; do IFS=: read -r name dir <<<"$i"
    echo "== build cloudpulse/$name"; DOCKER_BUILDKIT=1 docker build -q -t cloudpulse/$name:0.1.0 "$dir" >/dev/null
  done
fi

kind get clusters 2>/dev/null | grep -qx $CLUSTER || kind create cluster --config kubernetes/kind-cluster.yaml --wait 120s
kubectl config use-context kind-$CLUSTER >/dev/null

for i in "${IMAGES[@]}"; do IFS=: read -r name _ <<<"$i"; echo "== load $name into kind"; kind load docker-image cloudpulse/$name:0.1.0 --name $CLUSTER >/dev/null; done
# third-party images (postgres, curl, Pub/Sub emulator, metrics-server) are pulled by the nodes themselves:
# `kind load` fails on multi-platform digests with Docker 29, and only our own images need side-loading.

kubectl apply -f kubernetes/addons/metrics-server.yaml >/dev/null
kubectl get ns cloudpulse >/dev/null 2>&1 || kubectl create ns cloudpulse >/dev/null
# secrets and the init script are created imperatively: nothing secret is ever committed
kubectl -n cloudpulse create secret generic cloudpulse-db --from-literal=password="$POSTGRES_PASSWORD" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
# Identity secrets (generated once, kept only in the cluster; nothing secret is ever committed):
#  - an RSA signing key for JWTs
#  - demo users with BCRYPT HASHES only; the plaintext demo passwords are written once to .run/identity-credentials.txt (git-ignored, mode 600)
if ! kubectl -n cloudpulse get secret identity-signing >/dev/null 2>&1; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null | kubectl -n cloudpulse create secret generic identity-signing --from-file=key.pem=/dev/stdin >/dev/null
fi
if ! kubectl -n cloudpulse get secret identity-users >/dev/null 2>&1; then
  mkdir -p .run; umask 077; : > .run/identity-credentials.txt
  users_json="["
  for entry in viewer:VIEWER operator:OPERATOR admin:ADMIN; do
    u="${entry%%:*}"; r="${entry##*:}"; pw="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)"
    hash="$(htpasswd -bnBC 10 "" "$pw" | tr -d ':\n' | sed 's/^\$2y/\$2a/')"
    echo "$u $pw ($r)" >> .run/identity-credentials.txt
    users_json="$users_json{\"username\":\"$u\",\"bcrypt\":\"$hash\",\"roles\":[\"$r\"]},"
  done
  users_json="${users_json%,}]"
  printf '%s' "$users_json" | kubectl -n cloudpulse create secret generic identity-users --from-file=users.json=/dev/stdin >/dev/null
  echo "Demo logins written to .run/identity-credentials.txt (viewer / operator / admin)"
fi
kubectl -n cloudpulse create configmap pubsub-init-script --from-file=pubsub-init.sh=scripts/pubsub-init.sh --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl delete job pubsub-init -n cloudpulse --ignore-not-found >/dev/null
kubectl apply -k kubernetes/overlays/local

echo "== waiting for rollouts"
for d in postgres; do kubectl -n cloudpulse rollout status statefulset/$d --timeout=240s; done
kubectl -n cloudpulse rollout status deployment/pubsub-emulator --timeout=240s
kubectl -n cloudpulse wait --for=condition=complete job/pubsub-init --timeout=240s
for d in order-service inventory-service operations-service analytics-sink ai-incident-assistant identity-service remediation-controller dashboard; do kubectl -n cloudpulse rollout status deployment/$d --timeout=300s; done
kubectl -n cloudpulse get pods -o wide
echo "Open the dashboard:  kubectl -n cloudpulse port-forward svc/dashboard 8088:8080   ->  http://localhost:8088"
