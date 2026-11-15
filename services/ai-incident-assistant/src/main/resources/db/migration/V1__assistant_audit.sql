-- Every answer is stored with the evidence it was built from (auditability of AI output).
CREATE TABLE answers (
    id            UUID PRIMARY KEY,
    asked_at      TIMESTAMPTZ  NOT NULL,
    question      VARCHAR(500) NOT NULL,
    intent        VARCHAR(32)  NOT NULL,
    service       VARCHAR(64),
    provider      VARCHAR(120) NOT NULL,
    confidence    VARCHAR(10)  NOT NULL,
    answer        TEXT         NOT NULL,
    response_json TEXT         NOT NULL
);
CREATE INDEX idx_answers_asked_at ON answers (asked_at DESC);
