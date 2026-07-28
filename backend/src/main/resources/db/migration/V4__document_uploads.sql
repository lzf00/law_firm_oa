CREATE TABLE document_upload_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    document_id UUID REFERENCES documents(id),
    matter_id UUID REFERENCES matters(id),
    contract_id UUID REFERENCES contracts(id),
    logical_name VARCHAR(300) NOT NULL,
    document_type VARCHAR(80) NOT NULL,
    original_filename VARCHAR(300) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    expected_size BIGINT NOT NULL,
    expected_sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(500) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_by UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK (expected_size > 0 AND expected_size <= 209715200),
    CHECK ((matter_id IS NOT NULL)::integer + (contract_id IS NOT NULL)::integer = 1),
    CHECK (status IN ('PENDING', 'COMPLETED', 'EXPIRED', 'FAILED'))
);

CREATE INDEX idx_upload_sessions_expiry
    ON document_upload_sessions(status, expires_at);
CREATE INDEX idx_upload_sessions_actor
    ON document_upload_sessions(created_by, created_at DESC);

