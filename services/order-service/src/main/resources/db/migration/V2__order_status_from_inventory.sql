ALTER TABLE orders ADD COLUMN status_reason VARCHAR(200);

-- Idempotent consumer of inventory.updated: one row per processed eventId.
CREATE TABLE processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
