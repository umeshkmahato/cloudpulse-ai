# Real-time analytics pipeline (Phase 7)

## Architecture
```
order-service ─► orders-created ──────────┐
inventory-service ─► inventory-updated ───┼─► *-analytics-sub ─► analytics-sink ─► analytics store ─► operations-service ─► dashboard
operations API / agents ─► application-metrics ┘   (ack after write, nack ─► DLQ)    (Postgres | BigQuery)    /api/v1/analytics/*
```
- **Local (verified):** `data-pipeline/analytics-sink` (Spring Boot) streams three subscriptions into Postgres schema `analytics`.
- **Cloud (NOT verified):** the same sink with `SINK_TARGET=bigquery` writes via streaming `insertAll` to dataset `cloudpulse_analytics`
  (tables created by the Terraform `bigquery` module, same columns). The BigQuery adapter compiles but has never run against real BigQuery.
- **Dataflow/Beam:** not implemented. A Beam job would add windowed aggregation and autoscaling at the cost of always-on workers.

## Why a sink service and not Dataflow now
| Option | Verdict |
|---|---|
| Dataflow (Beam) | Right at high volume or for stateful windowing/joins; workers bill while running; heavy for POC volumes. Deferred, needs approval |
| **Pub/Sub BigQuery subscription** | Cheapest cloud path for raw event landing: no code, no workers. Use it in cloud for raw tables; keep the sink for validation/enrichment |
| Custom sink (this) | Full control (validation, idempotency, labelling), runs anywhere incl. laptop; you operate it |

## Data model (mirrors BigQuery)
`order_events`, `inventory_events`, `metric_samples`: `event_id` primary key (idempotent, `ON CONFLICT DO NOTHING`),
`occurred_at` (event time), `ingested_at` (processing time), payload JSON for audit/replay, `synthetic` flag on metrics.
Cloud tables are partitioned by day on event time and clustered (`order_id` or `service, metric`).

## Delivery semantics
At-least-once from Pub/Sub; the sink dedupes on `event_id` (Postgres exact; BigQuery best-effort via `insertId`, so cloud
queries should dedupe with `QUALIFY ROW_NUMBER() OVER (PARTITION BY event_id ...) = 1`). Invalid events are nacked and
dead-lettered after 5 attempts (same policy as the other consumers).

## Metrics exposed
Business counts, order value, outcomes + rejection reasons (error distribution), metric trends, **event processing latency**
(`ingested_at - occurred_at`, p50/p95) and sink Prometheus metrics `sink_events_written_total`, `sink_events_duplicates_total`,
`sink_pipeline_latency_seconds`.

## Operations APIs (`operations-service`)
`GET /api/v1/analytics/overview | orders/trend | inventory/outcomes | metrics/trend | pipeline/latency | events/recent`.
Operations only reads the analytics store; the sink is the only writer. In Postgres-local mode this is a cross-schema read
(an accepted shortcut); in cloud it becomes BigQuery queries (billed per bytes scanned: always filter by partition).

## Synthetic data
`scripts/simulate-telemetry.py` generates normal baselines and the brief's overload ramp (100→1000 rpm, CPU 40→90%,
DB 50→95%, latency 300→2500 ms). Every sample carries `synthetic=true`, is stored with that flag, and APIs return it, so the
dashboard can label it. It is never presented as GCP telemetry.

## Interview notes
- **Why event time vs processing time?** Latency and windows must use `occurred_at`; `ingested_at` exposes pipeline lag and backlog (verified: a drained backlog shows as high p50 latency).
- **Scaling limits:** one sink instance with N streaming pulls; scale horizontally (Pub/Sub load-balances); Postgres insert throughput is the local ceiling; BigQuery streaming inserts have quotas and per-GB cost.
- **Failure modes:** store down ⇒ nacks ⇒ backlog grows (alert on `oldest_unacked_message_age`), no loss; poison events ⇒ DLQ.
- **Cost:** streaming inserts and query scans are billed; partition filters and the 30-day partition expiry in dev bound it. Pricing unverified.
- **10M users:** Pub/Sub BigQuery subscriptions or Dataflow, partitioned/clustered tables, pre-aggregated materialized views for dashboards, separate OLTP and analytics projects.
