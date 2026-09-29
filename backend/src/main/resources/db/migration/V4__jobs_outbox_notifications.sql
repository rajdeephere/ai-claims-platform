-- Phase 3: database job queue, transactional outbox, in-app notifications

CREATE TABLE job (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type           VARCHAR(40)   NOT NULL,
    payload        JSONB         NOT NULL DEFAULT '{}',
    claim_id       BIGINT        REFERENCES claim (id),
    status         VARCHAR(10)   NOT NULL,
    attempts       INTEGER       NOT NULL DEFAULT 0,
    max_attempts   INTEGER       NOT NULL,
    due_at         TIMESTAMPTZ   NOT NULL,       -- a timer is just a job due later
    locked_by      VARCHAR(100),
    locked_until   TIMESTAMPTZ,                  -- lease: a crashed worker's job comes back after this
    last_error     VARCHAR(2000),
    dedup_key      VARCHAR(150),
    correlation_id VARCHAR(64),                  -- the request that caused the job, carried into its logs
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    completed_at   TIMESTAMPTZ,
    CONSTRAINT ck_job_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_job_attempts CHECK (attempts >= 0 AND max_attempts >= 1)
);

-- the same logical job is scheduled at most once ("VERIFY_POLICY:42")
CREATE UNIQUE INDEX ux_job_dedup_key ON job (dedup_key) WHERE dedup_key IS NOT NULL;
-- the runner's two questions: what is due, and whose lease has expired
CREATE INDEX ix_job_due ON job (due_at) WHERE status = 'PENDING';
CREATE INDEX ix_job_lease ON job (locked_until) WHERE status = 'RUNNING';
CREATE INDEX ix_job_claim ON job (claim_id, type);
CREATE INDEX ix_job_failed ON job (updated_at DESC) WHERE status = 'FAILED';

CREATE TABLE outbox_event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  VARCHAR(30)   NOT NULL,
    aggregate_id    BIGINT        NOT NULL,
    event_type      VARCHAR(40)   NOT NULL,
    payload         JSONB         NOT NULL,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    last_error      VARCHAR(2000),
    failed_at       TIMESTAMPTZ
);

CREATE INDEX ix_outbox_pending ON outbox_event (next_attempt_at, id) WHERE published_at IS NULL AND failed_at IS NULL;

-- one row per (event, listener) that has handled it: a redelivered event is skipped by that listener
CREATE TABLE processed_event (
    event_id     BIGINT      NOT NULL REFERENCES outbox_event (id) ON DELETE CASCADE,
    listener     VARCHAR(60) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, listener)
);

CREATE TABLE notification (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recipient_user_id BIGINT        NOT NULL REFERENCES app_user (id),
    claim_id          BIGINT        REFERENCES claim (id),
    subject           VARCHAR(200)  NOT NULL,
    body              VARCHAR(2000) NOT NULL,
    source_event_id   BIGINT        NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    read_at           TIMESTAMPTZ,
    -- a second delivery of the same event can't notify the same person twice
    CONSTRAINT ux_notification_event_recipient UNIQUE (source_event_id, recipient_user_id)
);

CREATE INDEX ix_notification_recipient ON notification (recipient_user_id, created_at DESC);
