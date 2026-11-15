#!/bin/sh
# Idempotently creates CloudPulse topics, subscriptions, retry policy and dead-letter topics
# on a Pub/Sub endpoint (emulator by default). POSIX sh + curl only.
# Host usage: PUBSUB_HOST=localhost:8085 sh scripts/pubsub-init.sh
set -eu
HOST="${PUBSUB_HOST:-localhost:8085}"
PROJECT="${GCP_PROJECT_ID:-cloudpulse-ai-poc}"
BASE="http://$HOST/v1/projects/$PROJECT"
MAX_ATTEMPTS=5

put() { # path, json body
  code=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H 'Content-Type: application/json' -d "$2" "$BASE/$1")
  case "$code" in 200) echo "created  $1" ;; 409) echo "exists   $1" ;; *) echo "FAILED($code) $1" >&2; exit 1 ;; esac
}

# topic : consumer subscription
PAIRS="orders-created:orders-created-inventory-sub
orders-created:orders-created-analytics-sub
inventory-updated:inventory-updated-analytics-sub
inventory-updated:inventory-updated-order-sub
payment-events:payment-events-ops-sub
application-metrics:application-metrics-ops-sub
application-metrics:application-metrics-analytics-sub
infrastructure-alerts:infrastructure-alerts-ops-sub
security-events:security-events-ops-sub
remediation-events:remediation-events-ops-sub"

echo "$PAIRS" | while IFS=: read -r topic sub; do
  put "topics/$topic" '{}'
  put "topics/$topic-dlq" '{}'
  put "subscriptions/$topic-dlq-sub" "{\"topic\":\"projects/$PROJECT/topics/$topic-dlq\",\"ackDeadlineSeconds\":30}"
  put "subscriptions/$sub" "{\"topic\":\"projects/$PROJECT/topics/$topic\",\"ackDeadlineSeconds\":30,\
\"retryPolicy\":{\"minimumBackoff\":\"5s\",\"maximumBackoff\":\"300s\"},\
\"deadLetterPolicy\":{\"deadLetterTopic\":\"projects/$PROJECT/topics/$topic-dlq\",\"maxDeliveryAttempts\":$MAX_ATTEMPTS}}"
done
echo "Pub/Sub init complete ($HOST, project $PROJECT)"
