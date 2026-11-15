# Messaging design (Phase 6)

## Topology
| Topic | Producer | Subscriptions (consumer) |
|---|---|---|
| orders-created | order-service (outbox) | orders-created-inventory-sub (inventory-service) |
| inventory-updated | inventory-service (outbox) | inventory-updated-order-sub (order-service), inventory-updated-analytics-sub (Phase 7 BigQuery sink) |
| application-metrics | operations-service ingest API / agents | application-metrics-ops-sub (operations-service) |
| payment-events, infrastructure-alerts, security-events, remediation-events | later phases | `*-ops-sub` |

Every topic has `<topic>-dlq` and `<topic>-dlq-sub`. Contracts: `architecture/event-schemas/*.schema.json`
(enforced by `EventContractTest` in each producing service) and `architecture/event-schemas.md`.

## Delivery guarantees (end to end)
1. **Producer**: order/event written in one DB transaction (outbox); relay publishes after commit, at-least-once, row-locked with `SKIP LOCKED`.
2. **Broker**: Pub/Sub is at-least-once; redelivery and (without ordering keys) reordering are possible.
3. **Consumer**: dedupe table keyed by `eventId` (and business key where relevant) in the same transaction as the side effect; **ack after commit**, **nack on any failure**.
4. **Retry**: subscription retry policy backoff 5s to 300s; after 5 delivery attempts the message goes to the DLQ.
5. **Replay**: `scripts/dlq-replay.sh <topic>` republishes DLQ messages to the original topic once the cause is fixed, acking the DLQ copy only after the republish succeeded.

## Failure matrix
| Failure | Behaviour |
|---|---|
| Broker down while ordering | Order accepted; outbox rows stay pending (`outbox_events_pending` metric) and publish on recovery |
| Consumer crash after commit, before ack | Redelivery; deduped by `eventId` |
| Malformed / invalid event | Nack x5 then DLQ (verified: ~40s); never blocks the stream |
| inventory.updated for unknown order | Retried then DLQ (verified) |
| Out-of-order / late event for finalized order | Ignored by terminal-state guard |
| Poison loops | Bounded by max delivery attempts |

## Real GCP switch
Unset `PUBSUB_EMULATOR_HOST`; services then use Application Default Credentials (Workload Identity on GKE).
Topics/subscriptions are created by Terraform (Phase 2 code) instead of `scripts/pubsub-init.sh`. Not yet verified against real
Pub/Sub (deferred until the GCP project is enabled).

## Interview notes
- **Why outbox, not publish-in-transaction?** No distributed transaction between Postgres and Pub/Sub; outbox gives atomicity with at-least-once delivery plus idempotent consumers = effectively-once effect.
- **Why Pub/Sub over Kafka?** Serverless, per-message ack/DLQ/retry built in, no cluster to operate; Kafka wins for strict partition ordering, replay by offset, very high throughput.
- **Ordering**: not guaranteed here; correctness relies on idempotency and state guards. If strict per-order ordering were needed: ordering keys (orderId) with message ordering enabled.
- **Scaling limits**: relay is a single poller per instance (SKIP LOCKED allows more); outbox table needs pruning; Pub/Sub quotas apply per project.
- **Security risks**: unauthenticated emulator locally; in cloud use per-service identities with publisher/subscriber roles on specific topics only (Phase 13).
- **Cost**: Pub/Sub is billed by data volume; DLQ/replay costs follow the same model. Pricing must be verified before deployment.
- **10M users**: ordering keys, regional topics with message storage policy, batch publishers, partitioned outbox or CDC (Datastream/Debezium), schema registry.
