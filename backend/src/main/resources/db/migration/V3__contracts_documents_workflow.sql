CREATE TABLE contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    contract_number VARCHAR(80) NOT NULL,
    title VARCHAR(300) NOT NULL,
    client_id UUID REFERENCES clients(id),
    responsible_user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    effective_date DATE,
    expiry_date DATE,
    amount NUMERIC(18,2),
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id, contract_number),
    CHECK (status IN ('DRAFT', 'REVIEWING', 'APPROVED', 'SIGNED', 'EXPIRED', 'TERMINATED', 'ARCHIVED'))
);

CREATE INDEX idx_contracts_org_status ON contracts(organization_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_contracts_responsible ON contracts(responsible_user_id, status) WHERE deleted_at IS NULL;

CREATE TABLE contract_matters (
    contract_id UUID NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
    matter_id UUID NOT NULL REFERENCES matters(id),
    PRIMARY KEY (contract_id, matter_id)
);

CREATE INDEX idx_contract_matters_matter ON contract_matters(matter_id);

CREATE TABLE contract_members (
    contract_id UUID NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    member_role VARCHAR(32) NOT NULL,
    can_download BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (contract_id, user_id)
);

CREATE INDEX idx_contract_members_user ON contract_members(user_id);

CREATE TABLE contract_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id UUID NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    summary VARCHAR(500),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (contract_id, version_number),
    CHECK (status IN ('DRAFT', 'REVIEWED', 'FINAL', 'SUPERSEDED', 'ARCHIVED'))
);

CREATE UNIQUE INDEX uq_contract_current_final
    ON contract_versions(contract_id)
    WHERE status = 'FINAL';

CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    matter_id UUID REFERENCES matters(id),
    contract_id UUID REFERENCES contracts(id),
    logical_name VARCHAR(300) NOT NULL,
    document_type VARCHAR(80) NOT NULL,
    confidentiality_level VARCHAR(32) NOT NULL DEFAULT 'CONFIDENTIAL',
    current_version_id UUID,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CHECK ((matter_id IS NOT NULL)::integer + (contract_id IS NOT NULL)::integer = 1)
);

CREATE INDEX idx_documents_matter ON documents(matter_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_documents_contract ON documents(contract_id) WHERE deleted_at IS NULL;

CREATE TABLE document_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    version_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    signature_status VARCHAR(32) NOT NULL DEFAULT 'UNSIGNED',
    object_key VARCHAR(500) NOT NULL,
    original_filename VARCHAR(300) NOT NULL,
    content_type VARCHAR(150) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    source_version_id UUID REFERENCES document_versions(id),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_number),
    CHECK (size_bytes >= 0),
    CHECK (version_status IN ('DRAFT', 'REVIEWED', 'FINAL', 'SUPERSEDED', 'ARCHIVED')),
    CHECK (signature_status IN ('UNSIGNED', 'PENDING', 'SIGNED', 'REJECTED', 'INVALID'))
);

ALTER TABLE documents
    ADD CONSTRAINT fk_documents_current_version
    FOREIGN KEY (current_version_id) REFERENCES document_versions(id);

CREATE UNIQUE INDEX uq_document_current_final
    ON document_versions(document_id)
    WHERE version_status = 'FINAL';
CREATE INDEX idx_document_versions_hash ON document_versions(sha256);

CREATE TABLE contract_version_documents (
    contract_version_id UUID NOT NULL REFERENCES contract_versions(id) ON DELETE CASCADE,
    document_version_id UUID NOT NULL REFERENCES document_versions(id),
    PRIMARY KEY (contract_version_id, document_version_id)
);

CREATE INDEX idx_contract_version_documents_doc ON contract_version_documents(document_version_id);

CREATE TABLE document_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    permission VARCHAR(32) NOT NULL,
    granted_by UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, user_id, permission),
    CHECK (permission IN ('PREVIEW', 'DOWNLOAD', 'EDIT', 'SHARE'))
);

CREATE INDEX idx_document_grants_user ON document_grants(user_id, expires_at);

CREATE TABLE document_access_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id),
    document_version_id UUID REFERENCES document_versions(id),
    user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(32) NOT NULL,
    result VARCHAR(32) NOT NULL,
    ip_address INET,
    user_agent VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN ('METADATA', 'PREVIEW', 'DOWNLOAD', 'UPLOAD', 'EXPORT', 'SHARE')),
    CHECK (result IN ('ALLOWED', 'DENIED', 'FAILED'))
);

CREATE INDEX idx_document_access_document ON document_access_logs(document_id, occurred_at DESC);
CREATE INDEX idx_document_access_user ON document_access_logs(user_id, occurred_at DESC);

CREATE TABLE external_shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id),
    token_hash CHAR(64) NOT NULL UNIQUE,
    approved_by UUID NOT NULL REFERENCES users(id),
    created_by UUID NOT NULL REFERENCES users(id),
    expires_at TIMESTAMPTZ NOT NULL,
    max_downloads INTEGER NOT NULL DEFAULT 1,
    download_count INTEGER NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (max_downloads > 0),
    CHECK (download_count >= 0)
);

CREATE INDEX idx_external_shares_document ON external_shares(document_id, expires_at);

CREATE TABLE workflow_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    business_type VARCHAR(80) NOT NULL,
    business_id UUID NOT NULL,
    process_definition_key VARCHAR(150) NOT NULL,
    process_instance_id VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    started_by UUID NOT NULL REFERENCES users(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    UNIQUE (business_type, business_id, process_instance_id)
);

CREATE INDEX idx_workflow_business ON workflow_links(business_type, business_id);

CREATE TABLE business_state_transitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_type VARCHAR(80) NOT NULL,
    business_id UUID NOT NULL,
    from_state VARCHAR(50),
    to_state VARCHAR(50) NOT NULL,
    workflow_link_id UUID REFERENCES workflow_links(id),
    changed_by UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(500),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_business_transitions ON business_state_transitions(business_type, business_id, changed_at);

CREATE TABLE seals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(150) NOT NULL,
    seal_type VARCHAR(80) NOT NULL,
    custodian_user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE seal_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    seal_id UUID NOT NULL REFERENCES seals(id),
    matter_id UUID REFERENCES matters(id),
    contract_id UUID REFERENCES contracts(id),
    purpose VARCHAR(500) NOT NULL,
    copies INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    requested_by UUID NOT NULL REFERENCES users(id),
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (copies > 0),
    CHECK ((matter_id IS NOT NULL)::integer + (contract_id IS NOT NULL)::integer = 1)
);

CREATE INDEX idx_seal_requests_status ON seal_requests(organization_id, status, created_at DESC);

CREATE TABLE archive_volumes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    archive_number VARCHAR(100) NOT NULL,
    title VARCHAR(300) NOT NULL,
    retention_policy_code VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    archived_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, archive_number)
);

CREATE TABLE archive_items (
    archive_volume_id UUID NOT NULL REFERENCES archive_volumes(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES documents(id),
    sequence_number INTEGER NOT NULL,
    PRIMARY KEY (archive_volume_id, document_id),
    UNIQUE (archive_volume_id, sequence_number)
);

CREATE INDEX idx_archive_items_document ON archive_items(document_id);

CREATE TABLE retention_holds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    matter_id UUID REFERENCES matters(id),
    document_id UUID REFERENCES documents(id),
    reason TEXT NOT NULL,
    placed_by UUID NOT NULL REFERENCES users(id),
    placed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by UUID REFERENCES users(id),
    released_at TIMESTAMPTZ,
    CHECK ((matter_id IS NOT NULL)::integer + (document_id IS NOT NULL)::integer = 1)
);

CREATE INDEX idx_retention_holds_matter ON retention_holds(matter_id) WHERE released_at IS NULL;
CREATE INDEX idx_retention_holds_document ON retention_holds(document_id) WHERE released_at IS NULL;

