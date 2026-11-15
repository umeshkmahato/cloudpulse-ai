CREATE TABLE remediations (
    id                 UUID PRIMARY KEY,
    incident_id        UUID         NOT NULL,
    service            VARCHAR(64)  NOT NULL,
    action             VARCHAR(32)  NOT NULL,          -- predefined catalogue only: SCALE_DEPLOYMENT
    status             VARCHAR(16)  NOT NULL,          -- PROPOSED APPROVED EXECUTING MONITORING RECOVERED NOT_RECOVERED REJECTED EXPIRED BLOCKED FAILED
    deployment         VARCHAR(64)  NOT NULL,
    namespace          VARCHAR(64)  NOT NULL,
    from_replicas      INT          NOT NULL,
    to_replicas        INT          NOT NULL,
    risk_score         DOUBLE PRECISION NOT NULL,
    incident_kind      VARCHAR(16)  NOT NULL,
    rationale          TEXT         NOT NULL,
    guardrail_notes    TEXT         NOT NULL,          -- JSON array: checks applied and their result
    decided_by         VARCHAR(80),
    decision_comment   VARCHAR(300),
    decided_at         TIMESTAMPTZ,
    policy             VARCHAR(80),                    -- set when auto-approved by the explicit policy
    executed_at        TIMESTAMPTZ,
    observed_replicas  INT,
    recovered_at       TIMESTAMPTZ,
    time_to_recover_s  BIGINT,
    outcome            VARCHAR(24),
    outcome_detail     TEXT,
    synthetic          BOOLEAN      NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    UNIQUE (incident_id, action)                       -- one proposal per incident and action: idempotent proposer
);
CREATE INDEX idx_remediations_status ON remediations (status, created_at DESC);

-- Append-only audit log: rows can never be changed or removed (enforced by trigger, not just by convention).
CREATE TABLE remediation_audit (
    id             BIGSERIAL PRIMARY KEY,
    remediation_id UUID,
    at             TIMESTAMPTZ NOT NULL,
    actor          VARCHAR(80) NOT NULL,               -- operator name, policy name, or "controller"
    event          VARCHAR(32) NOT NULL,
    details        TEXT        NOT NULL
);
CREATE INDEX idx_audit_remediation ON remediation_audit (remediation_id, id);

CREATE FUNCTION forbid_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'remediation_audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER audit_no_update BEFORE UPDATE OR DELETE ON remediation_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_change();

CREATE TABLE controller_state (
    key        VARCHAR(40) PRIMARY KEY,
    value      VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
INSERT INTO controller_state (key, value, updated_at) VALUES ('kill_switch', 'off', now());
