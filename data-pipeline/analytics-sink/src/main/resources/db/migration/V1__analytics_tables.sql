-- Local stand-in for the BigQuery dataset `cloudpulse_analytics` (same tables/columns as the Terraform bigquery module).
-- event_id is the primary key: Pub/Sub is at-least-once, so inserts are ON CONFLICT DO NOTHING.
CREATE TABLE order_events (
    event_id       TEXT PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT,
    order_id       TEXT NOT NULL,
    customer_id    TEXT,
    total_amount   NUMERIC(14, 2),
    currency       VARCHAR(3),
    payload        JSONB,
    ingested_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_order_events_time ON order_events (occurred_at DESC);
CREATE INDEX idx_order_events_order ON order_events (order_id);

CREATE TABLE inventory_events (
    event_id       TEXT PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL,
    correlation_id TEXT,
    order_id       TEXT NOT NULL,
    status         TEXT,
    reason         TEXT,
    payload        JSONB,
    ingested_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_inventory_events_time ON inventory_events (occurred_at DESC);

CREATE TABLE metric_samples (
    event_id    TEXT PRIMARY KEY,
    occurred_at TIMESTAMPTZ      NOT NULL,   -- event time of the envelope (for pipeline latency)
    observed_at TIMESTAMPTZ      NOT NULL,   -- when the metric was measured
    service     TEXT             NOT NULL,
    metric      TEXT             NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    unit        TEXT,
    synthetic   BOOLEAN          NOT NULL,
    ingested_at TIMESTAMPTZ      NOT NULL
);
CREATE INDEX idx_metric_samples_series ON metric_samples (service, metric, observed_at DESC);
