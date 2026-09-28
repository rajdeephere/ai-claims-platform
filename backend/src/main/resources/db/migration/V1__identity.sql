-- Phase 1: users and refresh tokens

CREATE TABLE app_user (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username        VARCHAR(50)    NOT NULL,
    password_hash   VARCHAR(100)   NOT NULL,
    display_name    VARCHAR(100)   NOT NULL,
    role            VARCHAR(20)    NOT NULL,
    authority_limit NUMERIC(14, 2) NOT NULL DEFAULT 0,
    active          BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_app_user_role CHECK (role IN ('CLAIMANT', 'ADJUSTER', 'SUPERVISOR', 'SIU')),
    CONSTRAINT ck_app_user_limit CHECK (authority_limit >= 0)
);

-- usernames are case-insensitive: "Adjuster1" and "adjuster1" are the same user
CREATE UNIQUE INDEX ux_app_user_username ON app_user (lower(username));

CREATE TABLE refresh_token (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id),
    token_hash VARCHAR(64) NOT NULL,   -- SHA-256 hex; the raw token is never stored
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ux_refresh_token_hash UNIQUE (token_hash)
);

-- "revoke all active sessions of this user" (token reuse) only touches active rows
CREATE INDEX ix_refresh_token_user_active ON refresh_token (user_id) WHERE revoked_at IS NULL;
