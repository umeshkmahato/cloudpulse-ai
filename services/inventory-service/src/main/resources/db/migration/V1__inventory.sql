CREATE TABLE stock_items (
    sku         VARCHAR(64) PRIMARY KEY,
    description VARCHAR(200),
    available   INT         NOT NULL CHECK (available >= 0),
    updated_at  TIMESTAMPTZ NOT NULL
);

-- Idempotent consumer: one row per processed Pub/Sub eventId.
CREATE TABLE processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- One reservation decision per order (also guards against duplicates with a different eventId).
CREATE TABLE reservations (
    order_id   VARCHAR(32) PRIMARY KEY,
    status     VARCHAR(20) NOT NULL,
    reason     VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    topic          VARCHAR(100) NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    correlation_id VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- SEED DATA (synthetic, for local development only; not real inventory)
INSERT INTO stock_items (sku, description, available, updated_at) VALUES
  ('SKU-RED-1',  'Seed: red widget',   100, now()),
  ('SKU-BLUE-2', 'Seed: blue widget',   50, now()),
  ('SKU-LOW-3',  'Seed: low-stock gadget', 3, now());
