# Event schemas (v1)

All Pub/Sub messages are JSON using one envelope. Message attributes duplicate `eventId`, `eventType`, `correlationId` for filtering without parsing the body.

```json
{
  "eventId": "uuid",            // unique; consumers dedupe on this (idempotency)
  "eventType": "order.created",
  "eventVersion": 1,            // bump on breaking change; additive changes keep the version
  "occurredAt": "ISO-8601 UTC",
  "correlationId": "string",    // propagated from the X-Correlation-ID HTTP header
  "source": "order-service",
  "data": { }
}
```

## order.created (topic `orders-created`, producer: order-service)
```json
"data": {
  "orderId": "ORD-8D60300FBC17",
  "customerId": "CUST-42",
  "items": [{ "sku": "SKU-RED-1", "quantity": 2, "unitPrice": 19.99 }],
  "totalAmount": 39.98,
  "currency": "USD"
}
```

## inventory.updated (topic `inventory-updated`, producer: inventory-service)
```json
"data": {
  "orderId": "ORD-8D60300FBC17",
  "status": "RESERVED",                 // or REJECTED
  "reason": null,                       // e.g. INSUFFICIENT_STOCK:SKU-LOW-3, UNKNOWN_SKU:SKU-X
  "items": [{ "sku": "SKU-RED-1", "quantity": 2, "remainingAvailable": 96 }]
}
```

## metric.sample (topic `application-metrics`, producer: operations-service ingest API or agents)
```json
"data": { "service": "order-service", "metric": "cpu_pct", "value": 92.5, "unit": "pct",
          "synthetic": true,            // true = generated test data, never real GCP telemetry
          "observedAt": "ISO-8601 UTC" }
```
Allowed metrics: request_count, latency_ms, cpu_pct, memory_pct, pod_restarts, db_connections_pct,
error_rate_pct, event_latency_ms, business_transactions.

## Delivery semantics
- Producer: transactional outbox (order and event committed atomically), relayed at-least-once.
- Consumers: must be idempotent on `eventId`; ack only after successful processing.
- Subscriptions retry with 5s-300s backoff and dead-letter to `<topic>-dlq` after 5 attempts.

Later phases append: inventory.updated, metric.sample, alert.raised, security.event, remediation.* .
