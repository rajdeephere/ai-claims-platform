-- Phase 7: SIU cases, activities (tasks with an SLA), and information requests that expire.

-- an information request nobody answered within the deadline
ALTER TABLE info_request DROP CONSTRAINT ck_info_request_status;
ALTER TABLE info_request ADD CONSTRAINT ck_info_request_status
    CHECK (status IN ('OPEN', 'ANSWERED', 'CANCELLED', 'EXPIRED'));

CREATE TABLE siu_case (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id                BIGINT        NOT NULL REFERENCES claim (id),
    source                  VARCHAR(10)   NOT NULL,
    reason                  VARCHAR(2000) NOT NULL,
    referred_by             BIGINT        REFERENCES app_user (id),   -- null when the triage rule referred it
    referred_at             TIMESTAMPTZ   NOT NULL,
    fraud_score_at_referral INTEGER,
    status                  VARCHAR(10)   NOT NULL,
    investigator_id         BIGINT        REFERENCES app_user (id),
    findings                VARCHAR(4000),
    decided_at              TIMESTAMPTZ,
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_siu_case_source CHECK (source IN ('RULE', 'MANUAL')),
    CONSTRAINT ck_siu_case_status CHECK (status IN ('OPEN', 'CLEARED', 'CONFIRMED')),
    CONSTRAINT ck_siu_case_referrer CHECK ((source = 'MANUAL') = (referred_by IS NOT NULL)),
    -- a decided case always says who decided, when, and why
    CONSTRAINT ck_siu_case_decided CHECK ((status = 'OPEN') = (decided_at IS NULL)
        AND (status = 'OPEN' OR (investigator_id IS NOT NULL AND findings IS NOT NULL)))
);
-- one open investigation per claim
CREATE UNIQUE INDEX ux_siu_case_one_open ON siu_case (claim_id) WHERE status = 'OPEN';
CREATE INDEX ix_siu_case_status ON siu_case (status, referred_at);
CREATE INDEX ix_siu_case_claim ON siu_case (claim_id);

-- A task for a person (assignee) or for everyone with a role (candidate_role), with a due time.
CREATE TABLE activity (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id        BIGINT        NOT NULL REFERENCES claim (id),
    type            VARCHAR(30)   NOT NULL,
    subject         VARCHAR(200)  NOT NULL,
    assignee_id     BIGINT        REFERENCES app_user (id),
    candidate_role  VARCHAR(12),
    priority        VARCHAR(8)    NOT NULL,
    status          VARCHAR(10)   NOT NULL,
    due_at          TIMESTAMPTZ   NOT NULL,
    escalated_at    TIMESTAMPTZ,                -- set once, when the SLA was breached
    linked_id       BIGINT,                     -- the info request, SIU case or payment it is about
    created_at      TIMESTAMPTZ   NOT NULL,
    completed_at    TIMESTAMPTZ,
    completed_by    BIGINT        REFERENCES app_user (id),   -- null when completed or cancelled by the system
    outcome_note    VARCHAR(2000),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_activity_status CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_activity_priority CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_activity_role CHECK (candidate_role IS NULL OR candidate_role IN ('ADJUSTER', 'SUPERVISOR', 'SIU')),
    -- exactly one owner: a person or a role queue
    CONSTRAINT ck_activity_owner CHECK ((assignee_id IS NULL) <> (candidate_role IS NULL)),
    CONSTRAINT ck_activity_closed CHECK ((status = 'OPEN') = (completed_at IS NULL))
);
-- the same task is open at most once (also makes event replays harmless)
CREATE UNIQUE INDEX ux_activity_one_open ON activity (claim_id, type, coalesce(linked_id, 0)) WHERE status = 'OPEN';
CREATE INDEX ix_activity_assignee ON activity (assignee_id, status, due_at);
CREATE INDEX ix_activity_role ON activity (candidate_role, status, due_at);
CREATE INDEX ix_activity_claim ON activity (claim_id);
