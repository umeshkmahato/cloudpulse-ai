CREATE TABLE incidents (
    id               UUID PRIMARY KEY,
    service          VARCHAR(64)      NOT NULL,
    kind             VARCHAR(16)      NOT NULL,     -- DETECTED (anomalous now) | FORECAST (trend extrapolation, low confidence)
    severity         VARCHAR(10)      NOT NULL,
    risk_score       DOUBLE PRECISION NOT NULL,
    peak_risk        DOUBLE PRECISION NOT NULL,
    status           VARCHAR(10)      NOT NULL,     -- OPEN | RESOLVED
    opened_at        TIMESTAMPTZ      NOT NULL,
    updated_at       TIMESTAMPTZ      NOT NULL,
    resolved_at      TIMESTAMPTZ,
    clean_streak     INT              NOT NULL DEFAULT 0,
    summary          VARCHAR(500)     NOT NULL,
    affected         TEXT             NOT NULL,     -- JSON array
    evidence         TEXT             NOT NULL,     -- JSON: findings, forecasts, notes (everything needed to explain the decision)
    recommendations  TEXT             NOT NULL,     -- JSON array of predefined actions
    synthetic        BOOLEAN          NOT NULL
);
CREATE INDEX idx_incidents_status ON incidents (status, opened_at DESC);
CREATE UNIQUE INDEX uq_incidents_one_open_per_service ON incidents (service) WHERE status = 'OPEN';
