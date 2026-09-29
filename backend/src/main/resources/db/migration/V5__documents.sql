-- Phase 4: claim documents. The file itself lives in object storage; this row is its metadata.

CREATE TABLE document (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    claim_id              BIGINT        NOT NULL REFERENCES claim (id),
    storage_key           VARCHAR(200)  NOT NULL,
    file_name             VARCHAR(200)  NOT NULL,            -- sanitised display name, never used as a path
    declared_content_type VARCHAR(100)  NOT NULL,            -- what the uploader said
    content_type          VARCHAR(100),                      -- what the bytes are (detected at completion)
    declared_size_bytes   BIGINT        NOT NULL,
    size_bytes            BIGINT,
    sha256                VARCHAR(64),
    category              VARCHAR(20)   NOT NULL,            -- uploader's hint; the AI sets doc_type later
    doc_type              VARCHAR(30),
    status                VARCHAR(15)   NOT NULL,
    rejection_reason      VARCHAR(200),
    uploaded_by           BIGINT        NOT NULL REFERENCES app_user (id),
    visible_to_claimant   BOOLEAN       NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    uploaded_at           TIMESTAMPTZ,
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ux_document_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_document_status CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'PROCESSING', 'PROCESSED',
                                                    'FAILED', 'REJECTED')),
    CONSTRAINT ck_document_category CHECK (category IN ('DAMAGE_PHOTO', 'REPAIR_ESTIMATE', 'POLICE_REPORT',
                                                        'INVOICE', 'MEDICAL_REPORT', 'OTHER')),
    CONSTRAINT ck_document_size CHECK (declared_size_bytes > 0 AND (size_bytes IS NULL OR size_bytes > 0)),
    -- a verified document has its hash and real type
    CONSTRAINT ck_document_verified CHECK (status IN ('PENDING_UPLOAD', 'REJECTED')
                                           OR (sha256 IS NOT NULL AND content_type IS NOT NULL))
);

-- the same file on the same claim is stored once: a second upload returns the first document
CREATE UNIQUE INDEX ux_document_claim_sha256 ON document (claim_id, sha256)
    WHERE status IN ('UPLOADED', 'PROCESSING', 'PROCESSED', 'FAILED');
CREATE INDEX ix_document_claim ON document (claim_id, created_at);
-- the abandoned-upload sweeper
CREATE INDEX ix_document_pending ON document (created_at) WHERE status = 'PENDING_UPLOAD';
