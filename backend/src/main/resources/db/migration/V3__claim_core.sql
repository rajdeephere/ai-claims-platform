-- Phase 2: claims, information requests, notes, audit trail, FNOL idempotency

CREATE SEQUENCE claim_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE claim (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_number         VARCHAR(20)    NOT NULL,
    policy_number        VARCHAR(20)    NOT NULL,   -- as reported; deliberately no FK: unknown numbers are flagged, not refused
    claimant_user_id     BIGINT         REFERENCES app_user (id),   -- null when staff took the FNOL by phone
    reported_by_user_id  BIGINT         NOT NULL REFERENCES app_user (id),
    contact_name         VARCHAR(100)   NOT NULL,
    contact_phone        VARCHAR(20),
    loss_date            DATE           NOT NULL,
    loss_type            VARCHAR(20)    NOT NULL,
    loss_location        VARCHAR(200)   NOT NULL,
    description          VARCHAR(2000)  NOT NULL,
    injuries_reported    BOOLEAN        NOT NULL,
    estimated_loss       NUMERIC(14, 2),
    status               VARCHAR(15)    NOT NULL,
    close_outcome        VARCHAR(12),
    segment              VARCHAR(12),
    policy_verification  VARCHAR(12)    NOT NULL,
    flags                JSONB          NOT NULL DEFAULT '[]',
    fraud_score          INTEGER,
    assigned_adjuster_id BIGINT         REFERENCES app_user (id),
    created_at           TIMESTAMPTZ    NOT NULL,
    updated_at           TIMESTAMPTZ    NOT NULL,
    closed_at            TIMESTAMPTZ,
    version              BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ux_claim_number UNIQUE (claim_number),
    CONSTRAINT ck_claim_status CHECK (status IN ('SUBMITTED', 'ASSESSING', 'OPEN', 'AWAITING_INFO', 'SIU_REVIEW', 'CLOSED')),
    CONSTRAINT ck_claim_outcome CHECK (close_outcome IN ('PAID', 'DENIED', 'WITHDRAWN', 'NO_PAYMENT')),
    -- an outcome exactly when closed: the database refuses a half-closed claim
    CONSTRAINT ck_claim_closed_has_outcome CHECK ((status = 'CLOSED') = (close_outcome IS NOT NULL)),
    CONSTRAINT ck_claim_segment CHECK (segment IN ('FAST_TRACK', 'STANDARD', 'COMPLEX')),
    CONSTRAINT ck_claim_policy_verification CHECK (policy_verification IN ('PENDING', 'VERIFIED', 'UNVERIFIED')),
    CONSTRAINT ck_claim_fraud_score CHECK (fraud_score BETWEEN 0 AND 100),
    CONSTRAINT ck_claim_estimated_loss CHECK (estimated_loss >= 0)
);

-- work queues: "my open claims", status filters, and the claimant's own list
CREATE INDEX ix_claim_assignee_status ON claim (assigned_adjuster_id, status);
CREATE INDEX ix_claim_status_created ON claim (status, created_at DESC);
CREATE INDEX ix_claim_claimant ON claim (claimant_user_id, created_at DESC);

CREATE TABLE info_request (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id        BIGINT        NOT NULL REFERENCES claim (id),
    message         VARCHAR(2000) NOT NULL,
    requested_by    BIGINT        NOT NULL REFERENCES app_user (id),
    requested_at    TIMESTAMPTZ   NOT NULL,
    status          VARCHAR(10)   NOT NULL,
    response        VARCHAR(2000),
    responded_by    BIGINT        REFERENCES app_user (id),
    responded_at    TIMESTAMPTZ,
    CONSTRAINT ck_info_request_status CHECK (status IN ('OPEN', 'ANSWERED', 'CANCELLED'))
);

-- at most one open request per claim
CREATE UNIQUE INDEX ux_info_request_one_open ON info_request (claim_id) WHERE status = 'OPEN';

CREATE TABLE claim_note (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id   BIGINT        NOT NULL REFERENCES claim (id),
    author_id  BIGINT        NOT NULL REFERENCES app_user (id),
    body       VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_claim_note_claim ON claim_note (claim_id, created_at);

CREATE TABLE audit_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type    VARCHAR(30)  NOT NULL,
    entity_id      BIGINT       NOT NULL,
    claim_id       BIGINT,
    action         VARCHAR(40)  NOT NULL,
    actor_id       BIGINT,                      -- null = the system
    actor_name     VARCHAR(50)  NOT NULL,
    old_value      JSONB,
    new_value      JSONB,
    reason         VARCHAR(2000),
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_audit_event_claim ON audit_event (claim_id, occurred_at);

-- Append-only, enforced by the database: even a bug or a manual UPDATE can't rewrite history.
CREATE FUNCTION audit_event_is_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_is_append_only();

CREATE TABLE idempotency_record (
    idempotency_key VARCHAR(100) NOT NULL,
    user_id         BIGINT       NOT NULL REFERENCES app_user (id),
    operation       VARCHAR(40)  NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    resource_id     BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    -- keys are scoped per user: two users can't collide, and one can't replay another's request
    PRIMARY KEY (user_id, idempotency_key)
);
