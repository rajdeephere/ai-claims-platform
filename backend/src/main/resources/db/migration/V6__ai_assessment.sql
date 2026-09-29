-- Phase 5: AI assessments. Every LLM result is stored with what produced it (model, prompt version, the
-- hash of the exact file assessed) and what a person decided about it (accepted / overridden, with reason).

CREATE TABLE ai_assessment (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id        BIGINT        NOT NULL REFERENCES claim (id),
    document_id     BIGINT        REFERENCES document (id),
    kind            VARCHAR(20)   NOT NULL,
    status          VARCHAR(10)   NOT NULL,
    model           VARCHAR(100),
    prompt_version  VARCHAR(40),
    input_sha256    VARCHAR(64),
    output          JSONB,
    confidence      NUMERIC(4, 3),
    error           VARCHAR(500),
    tokens_in       INTEGER,
    tokens_out      INTEGER,
    latency_ms      INTEGER,
    review_status   VARCHAR(15)   NOT NULL,
    reviewed_by     BIGINT        REFERENCES app_user (id),
    reviewed_at     TIMESTAMPTZ,
    override_output JSONB,
    override_reason VARCHAR(2000),
    created_at      TIMESTAMPTZ   NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_ai_kind CHECK (kind IN ('DOCUMENT_EXTRACTION', 'FRAUD_SCORE')),
    CONSTRAINT ck_ai_status CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT ck_ai_review CHECK (review_status IN ('PENDING_REVIEW', 'ACCEPTED', 'OVERRIDDEN', 'NOT_APPLICABLE')),
    CONSTRAINT ck_ai_confidence CHECK (confidence BETWEEN 0 AND 1),
    -- a person who overrides the AI must say why
    CONSTRAINT ck_ai_override_reason CHECK (review_status <> 'OVERRIDDEN'
                                            OR (override_reason IS NOT NULL AND override_output IS NOT NULL)),
    CONSTRAINT ck_ai_document_kind CHECK ((kind = 'DOCUMENT_EXTRACTION') = (document_id IS NOT NULL))
);

-- one successful extraction per document (a retried job can't store a second one)
CREATE UNIQUE INDEX ux_ai_extraction_document ON ai_assessment (document_id)
    WHERE kind = 'DOCUMENT_EXTRACTION' AND status = 'COMPLETED';
CREATE INDEX ix_ai_assessment_claim ON ai_assessment (claim_id, created_at);

-- the policy's start date, captured at the policy check, so fraud scoring never calls the policy system
-- inside a transaction (signal: loss soon after the policy started)
ALTER TABLE claim ADD COLUMN policy_start DATE;
