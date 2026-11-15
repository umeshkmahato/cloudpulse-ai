#!/usr/bin/env bash
# Replays messages from <topic>-dlq back to <topic> (after the root cause is fixed).
# Usage: scripts/dlq-replay.sh orders-created [max=50]     (set PUBSUB_HOST for non-default emulator)
# Messages are republished with their original attributes plus replayedFrom=dlq. Consumers are
# idempotent on eventId, so replaying an already-processed event is harmless. Acks the DLQ message
# only after the republish succeeded (no loss if the script dies mid-way).
set -euo pipefail
TOPIC="${1:?usage: dlq-replay.sh <topic> [max]}"
MAX="${2:-50}"
HOST="${PUBSUB_HOST:-localhost:8085}"
PROJECT="${GCP_PROJECT_ID:-cloudpulse-ai-poc}"
BASE="http://$HOST/v1/projects/$PROJECT"
SUB="$TOPIC-dlq-sub"

pulled=$(curl -sf -X POST -H 'Content-Type: application/json' "$BASE/subscriptions/$SUB:pull" \
  -d "{\"maxMessages\":$MAX,\"returnImmediately\":true}")
count=0
while IFS=$'\t' read -r ack msg; do
  [ -z "$ack" ] && continue
  curl -sf -X POST -H 'Content-Type: application/json' "$BASE/topics/$TOPIC:publish" -d "{\"messages\":[$msg]}" >/dev/null
  curl -sf -X POST -H 'Content-Type: application/json' "$BASE/subscriptions/$SUB:acknowledge" -d "{\"ackIds\":[\"$ack\"]}" >/dev/null
  count=$((count+1))
done < <(echo "$pulled" | python3 -c '
import sys, json
for m in json.load(sys.stdin).get("receivedMessages", []):
    msg = m["message"]
    attrs = dict(msg.get("attributes", {})); attrs["replayedFrom"] = "dlq"
    print(m["ackId"] + "\t" + json.dumps({"data": msg["data"], "attributes": attrs}))')
echo "replayed $count message(s) from $SUB to $TOPIC"
