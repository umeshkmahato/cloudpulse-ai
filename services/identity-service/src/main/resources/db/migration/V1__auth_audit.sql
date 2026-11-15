-- Append-only security audit: logins, failures, lockouts, authorization denials, logouts.
CREATE TABLE auth_audit (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ  NOT NULL,
    event      VARCHAR(32)  NOT NULL,
    username   VARCHAR(80),
    source_ip  VARCHAR(64),
    detail     VARCHAR(300)
);
CREATE INDEX idx_auth_audit_at ON auth_audit (at DESC);

CREATE FUNCTION forbid_auth_audit_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'auth_audit is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER auth_audit_immutable BEFORE UPDATE OR DELETE ON auth_audit
    FOR EACH ROW EXECUTE FUNCTION forbid_auth_audit_change();
