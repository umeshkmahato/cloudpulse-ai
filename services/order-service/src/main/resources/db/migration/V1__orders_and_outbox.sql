CREATE TABLE orders (
    id               BIGSERIAL PRIMARY KEY,
    order_id         VARCHAR(32)    NOT NULL UNIQUE,
    customer_id      VARCHAR(64)    NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    total_amount     NUMERIC(14, 2) NOT NULL,
    currency         VARCHAR(3) NOT NULL,
    idempotency_key  VARCHAR(80) UNIQUE,
    created_at       TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_orders_created_at ON orders (created_at DESC);

CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_pk    BIGINT         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    sku         VARCHAR(64)    NOT NULL,
    quantity    INT            NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12, 2) NOT NULL CHECK (unit_price > 0)
);
CREATE INDEX idx_order_items_order ON order_items (order_pk);

-- Transactional outbox: the event is committed atomically with the order, then relayed to Pub/Sub.
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
