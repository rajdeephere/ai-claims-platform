-- Phase 6: financials. Exposures (one claimant x one coverage) hold the reserve; payments draw on it;
-- approvals are the maker-checker record; recoveries are money coming back. Guidewire ClaimCenter calls this
-- area "financials".

CREATE TABLE exposure (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id        BIGINT         NOT NULL REFERENCES claim (id),
    type            VARCHAR(20)    NOT NULL,
    coverage_type   VARCHAR(20)    NOT NULL,
    claimant_name   VARCHAR(100)   NOT NULL,
    status          VARCHAR(10)    NOT NULL,
    reserve_amount  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    paid_amount     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_by      BIGINT         NOT NULL REFERENCES app_user (id),
    created_at      TIMESTAMPTZ    NOT NULL,
    closed_at       TIMESTAMPTZ,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_exposure_type CHECK (type IN ('VEHICLE_DAMAGE', 'PROPERTY_DAMAGE', 'BODILY_INJURY')),
    CONSTRAINT ck_exposure_status CHECK (status IN ('OPEN', 'CLOSED')),
    -- money never goes out beyond what was reserved, whatever code path tries
    CONSTRAINT ck_exposure_amounts CHECK (reserve_amount >= 0 AND paid_amount >= 0 AND paid_amount <= reserve_amount)
);
CREATE INDEX ix_exposure_claim ON exposure (claim_id);

CREATE TABLE approval_request (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id         BIGINT         NOT NULL REFERENCES claim (id),
    kind             VARCHAR(15)    NOT NULL,
    target_id        BIGINT,                      -- payment id or exposure id; null for a denial
    amount           NUMERIC(14, 2),              -- what the approver's authority must cover
    reason           VARCHAR(2000)  NOT NULL,
    status           VARCHAR(10)    NOT NULL,
    requested_by     BIGINT         NOT NULL REFERENCES app_user (id),
    requested_at     TIMESTAMPTZ    NOT NULL,
    decided_by       BIGINT         REFERENCES app_user (id),
    decided_at       TIMESTAMPTZ,
    decision_reason  VARCHAR(2000),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_approval_kind CHECK (kind IN ('PAYMENT', 'RESERVE_CHANGE', 'DENIAL')),
    CONSTRAINT ck_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    -- maker-checker, enforced by the database as well as the code
    CONSTRAINT ck_approval_not_self CHECK (decided_by IS NULL OR decided_by <> requested_by)
);
-- one open request per target and kind (e.g. one pending reserve change per exposure)
CREATE UNIQUE INDEX ux_approval_pending_target ON approval_request (kind, coalesce(target_id, claim_id))
    WHERE status = 'PENDING';
CREATE INDEX ix_approval_status ON approval_request (status, requested_at);
CREATE INDEX ix_approval_claim ON approval_request (claim_id);

CREATE TABLE payment (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id            BIGINT         NOT NULL REFERENCES claim (id),
    exposure_id         BIGINT         NOT NULL REFERENCES exposure (id),
    amount              NUMERIC(14, 2) NOT NULL,
    payee_name          VARCHAR(100)   NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    idempotency_key     VARCHAR(64)    NOT NULL,  -- sent to the payment rail: a retry can't pay twice
    requested_by        BIGINT         NOT NULL REFERENCES app_user (id),
    approved_by         BIGINT         REFERENCES app_user (id),
    approval_request_id BIGINT         REFERENCES approval_request (id),
    external_reference  VARCHAR(64),
    failure_reason      VARCHAR(500),
    created_at          TIMESTAMPTZ    NOT NULL,
    issued_at           TIMESTAMPTZ,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ux_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'ISSUED', 'FAILED', 'REJECTED')),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_not_self_approved CHECK (approved_by IS NULL OR approval_request_id IS NULL
                                                   OR approved_by <> requested_by),
    CONSTRAINT ck_payment_issued CHECK ((status = 'ISSUED') = (external_reference IS NOT NULL))
);
CREATE INDEX ix_payment_claim ON payment (claim_id);
CREATE INDEX ix_payment_exposure_status ON payment (exposure_id, status);

CREATE TABLE recovery (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id    BIGINT         NOT NULL REFERENCES claim (id),
    amount      NUMERIC(14, 2) NOT NULL,
    source      VARCHAR(20)    NOT NULL,
    reference   VARCHAR(100),
    received_on DATE           NOT NULL,
    recorded_by BIGINT         NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_recovery_amount CHECK (amount > 0),
    CONSTRAINT ck_recovery_source CHECK (source IN ('THIRD_PARTY_INSURER', 'THIRD_PARTY', 'SALVAGE', 'OTHER'))
);
CREATE INDEX ix_recovery_claim ON recovery (claim_id);

-- The stub payment rail's own ledger (stands in for the bank's side). Keyed by our idempotency key, like a
-- real payment API: the same key twice returns the first payment instead of paying again.
CREATE TABLE payment_rail_stub (
    idempotency_key VARCHAR(64)    PRIMARY KEY,
    reference       VARCHAR(64)    NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL,
    payee_name      VARCHAR(100)   NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL
);
