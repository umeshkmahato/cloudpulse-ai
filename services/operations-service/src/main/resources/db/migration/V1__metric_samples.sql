CREATE TABLE metric_samples (
    id          BIGSERIAL PRIMARY KEY,
    event_id    VARCHAR(64)      NOT NULL UNIQUE,   -- idempotent ingest
    service     VARCHAR(64)      NOT NULL,
    metric      VARCHAR(64)      NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    unit        VARCHAR(16),
    synthetic   BOOLEAN          NOT NULL,          -- true = generated test data, NOT real telemetry
    observed_at TIMESTAMPTZ      NOT NULL,
    ingested_at TIMESTAMPTZ      NOT NULL
);
CREATE INDEX idx_samples_series ON metric_samples (service, metric, observed_at DESC);
