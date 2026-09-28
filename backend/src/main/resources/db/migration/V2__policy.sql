-- Phase 2: policy data behind the policy port. In a real carrier this lives in the policy administration
-- system (Guidewire PolicyCenter); here the stub adapter reads these tables.

CREATE TABLE policy (
    policy_number  VARCHAR(20)    PRIMARY KEY,
    product        VARCHAR(10)    NOT NULL,
    holder_user_id BIGINT         REFERENCES app_user (id),   -- links a portal user to their policy
    holder_name    VARCHAR(100)   NOT NULL,
    status         VARCHAR(12)    NOT NULL,
    effective_from DATE           NOT NULL,
    effective_to   DATE           NOT NULL,
    deductible     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    CONSTRAINT ck_policy_product CHECK (product IN ('AUTO', 'HOME')),
    CONSTRAINT ck_policy_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'LAPSED')),
    CONSTRAINT ck_policy_period CHECK (effective_to > effective_from),
    CONSTRAINT ck_policy_deductible CHECK (deductible >= 0)
);

CREATE TABLE policy_coverage (
    policy_number VARCHAR(20)    NOT NULL REFERENCES policy (policy_number),
    coverage_type VARCHAR(20)    NOT NULL,
    limit_amount  NUMERIC(14, 2) NOT NULL,
    PRIMARY KEY (policy_number, coverage_type),
    CONSTRAINT ck_coverage_type CHECK (coverage_type IN ('COLLISION', 'COMPREHENSIVE', 'GLASS', 'DWELLING', 'CONTENTS')),
    CONSTRAINT ck_coverage_limit CHECK (limit_amount > 0)
);
