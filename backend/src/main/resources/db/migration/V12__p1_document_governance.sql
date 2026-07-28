CREATE TABLE export_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    requested_by UUID NOT NULL REFERENCES users(id),
    resource_type VARCHAR(64) NOT NULL,
    filters JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    row_count INTEGER,
    object_key VARCHAR(1000),
    sha256 CHAR(64),
    error_message VARCHAR(500),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'EXPIRED')),
    CHECK (row_count IS NULL OR row_count >= 0)
);

CREATE INDEX idx_export_jobs_queue ON export_jobs(status, created_at);
CREATE INDEX idx_export_jobs_org ON export_jobs(organization_id, requested_by, created_at DESC);

CREATE TABLE document_renditions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    document_id UUID NOT NULL REFERENCES documents(id),
    document_version_id UUID NOT NULL REFERENCES document_versions(id),
    rendition_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    object_key VARCHAR(1000),
    content_type VARCHAR(150),
    failure_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE (document_version_id, rendition_type),
    CHECK (rendition_type IN ('PDF_PREVIEW', 'THUMBNAIL', 'OCR_TEXT')),
    CHECK (status IN ('PENDING', 'PROCESSING', 'AVAILABLE', 'FAILED'))
);

CREATE TABLE document_index_entries (
    document_version_id UUID PRIMARY KEY REFERENCES document_versions(id),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    provider VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    extracted_text TEXT,
    language VARCHAR(20),
    page_count INTEGER,
    failure_reason VARCHAR(500),
    indexed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    search_vector TSVECTOR GENERATED ALWAYS AS
        (to_tsvector('simple', COALESCE(extracted_text, ''))) STORED,
    CHECK (status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED')),
    CHECK (page_count IS NULL OR page_count >= 0)
);

CREATE INDEX idx_document_index_org_status ON document_index_entries(organization_id, status);
CREATE INDEX idx_document_index_search ON document_index_entries USING GIN(search_vector);

CREATE TABLE document_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID REFERENCES offices(id),
    code VARCHAR(80) NOT NULL,
    name_zh VARCHAR(200) NOT NULL,
    name_en VARCHAR(200) NOT NULL,
    category VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    current_version_id UUID,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id, code),
    CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE document_template_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES document_templates(id),
    version_number INTEGER NOT NULL,
    title VARCHAR(300) NOT NULL,
    body_markdown TEXT NOT NULL,
    variables JSONB NOT NULL DEFAULT '[]'::jsonb,
    change_note VARCHAR(500),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (template_id, version_number),
    CHECK (version_number > 0)
);

ALTER TABLE document_templates
    ADD CONSTRAINT fk_document_template_current_version
    FOREIGN KEY (current_version_id) REFERENCES document_template_versions(id);

CREATE TABLE clause_library (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID REFERENCES offices(id),
    code VARCHAR(80) NOT NULL,
    title_zh VARCHAR(300) NOT NULL,
    title_en VARCHAR(300) NOT NULL,
    category VARCHAR(80) NOT NULL,
    risk_level VARCHAR(24) NOT NULL DEFAULT 'STANDARD',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    current_version_id UUID,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id, code),
    CHECK (risk_level IN ('STANDARD', 'REVIEW_REQUIRED', 'RESTRICTED')),
    CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE clause_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clause_id UUID NOT NULL REFERENCES clause_library(id),
    version_number INTEGER NOT NULL,
    body_zh TEXT NOT NULL,
    body_en TEXT NOT NULL,
    guidance_zh TEXT,
    guidance_en TEXT,
    change_note VARCHAR(500),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (clause_id, version_number),
    CHECK (version_number > 0)
);

ALTER TABLE clause_library
    ADD CONSTRAINT fk_clause_current_version
    FOREIGN KEY (current_version_id) REFERENCES clause_versions(id);

CREATE TABLE retention_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID REFERENCES offices(id),
    resource_type VARCHAR(64) NOT NULL,
    document_type VARCHAR(80),
    retention_years INTEGER NOT NULL,
    disposition_action VARCHAR(24) NOT NULL DEFAULT 'REVIEW',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (retention_years BETWEEN 1 AND 100),
    CHECK (disposition_action IN ('REVIEW', 'DELETE', 'ARCHIVE'))
);

CREATE TABLE legal_holds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(300) NOT NULL,
    reason TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by UUID REFERENCES users(id),
    released_at TIMESTAMPTZ,
    release_reason TEXT,
    CHECK (status IN ('ACTIVE', 'RELEASED'))
);

CREATE TABLE legal_hold_resources (
    legal_hold_id UUID NOT NULL REFERENCES legal_holds(id),
    resource_type VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    added_by UUID NOT NULL REFERENCES users(id),
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (legal_hold_id, resource_type, resource_id)
);

CREATE INDEX idx_legal_hold_resource_lookup
    ON legal_hold_resources(resource_type, resource_id);

CREATE TABLE document_deletion_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    document_id UUID NOT NULL REFERENCES documents(id),
    requested_by UUID NOT NULL REFERENCES users(id),
    reason TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
    decided_by UUID REFERENCES users(id),
    decision_reason TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'BLOCKED_BY_HOLD', 'COMPLETED'))
);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('DOCUMENT_GOVERNANCE_VIEW', '查看文档治理', 'DOCUMENT_GOVERNANCE', 'VIEW'),
    ('DOCUMENT_GOVERNANCE_MANAGE', '管理文档治理', 'DOCUMENT_GOVERNANCE', 'MANAGE'),
    ('DOCUMENT_EXPORT', '导出文档清单', 'DOCUMENT', 'EXPORT'),
    ('DOCUMENT_DELETE', '删除文档', 'DOCUMENT', 'DELETE'),
    ('LEGAL_HOLD_MANAGE', '管理诉讼保全', 'LEGAL_HOLD', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('ADMIN', 'MANAGING_PARTNER', 'RECORDS_MANAGER')
  AND p.code IN (
      'DOCUMENT_GOVERNANCE_VIEW', 'DOCUMENT_GOVERNANCE_MANAGE',
      'DOCUMENT_EXPORT', 'DOCUMENT_DELETE', 'LEGAL_HOLD_MANAGE'
  )
ON CONFLICT DO NOTHING;
