ALTER TABLE conflict_checks
    ADD COLUMN office_id UUID REFERENCES offices(id),
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN decision_rationale TEXT,
    ADD COLUMN mitigation_plan TEXT,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE conflict_checks
    ADD CONSTRAINT conflict_checks_risk_level_check
    CHECK (risk_level IS NULL OR risk_level IN ('CLEAR', 'MEDIUM', 'HIGH'));

CREATE TABLE conflict_check_hits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conflict_check_id UUID NOT NULL REFERENCES conflict_checks(id) ON DELETE CASCADE,
    party_id UUID NOT NULL REFERENCES parties(id),
    party_name VARCHAR(300) NOT NULL,
    matter_id UUID NOT NULL REFERENCES matters(id),
    matter_number VARCHAR(80) NOT NULL,
    matter_title VARCHAR(300) NOT NULL,
    party_role VARCHAR(80) NOT NULL,
    side VARCHAR(32) NOT NULL,
    matter_status VARCHAR(32) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (conflict_check_id, party_id, matter_id, party_role)
);

CREATE INDEX idx_conflict_hits_check
    ON conflict_check_hits(conflict_check_id, captured_at);

CREATE TABLE conflict_check_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conflict_check_id UUID NOT NULL REFERENCES conflict_checks(id) ON DELETE CASCADE,
    action VARCHAR(32) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    risk_level VARCHAR(32),
    decision VARCHAR(32),
    rationale TEXT,
    mitigation_plan TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN ('CREATED', 'SUBMITTED', 'DECIDED')),
    CHECK (risk_level IS NULL OR risk_level IN ('CLEAR', 'MEDIUM', 'HIGH')),
    CHECK (decision IS NULL OR decision IN ('CLEAR', 'WAIVER_REQUIRED', 'REJECT'))
);

CREATE INDEX idx_conflict_actions_check_time
    ON conflict_check_actions(conflict_check_id, occurred_at);

ALTER TABLE contract_versions
    ADD COLUMN primary_document_version_id UUID REFERENCES document_versions(id),
    ADD COLUMN signed_document_version_id UUID REFERENCES document_versions(id),
    ADD COLUMN signature_status VARCHAR(32) NOT NULL DEFAULT 'UNSIGNED',
    ADD COLUMN finalized_by UUID REFERENCES users(id),
    ADD COLUMN finalized_at TIMESTAMPTZ,
    ADD COLUMN signed_by UUID REFERENCES users(id),
    ADD COLUMN signed_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE contracts
    DROP CONSTRAINT contracts_status_check;

ALTER TABLE contracts
    ADD CONSTRAINT contracts_status_check
    CHECK (status IN (
        'DRAFT', 'REVIEWING', 'APPROVED', 'REJECTED', 'SIGNED',
        'EXPIRED', 'TERMINATED', 'ARCHIVED'
    ));

ALTER TABLE contract_versions
    ADD CONSTRAINT contract_versions_signature_status_check
    CHECK (signature_status IN ('UNSIGNED', 'PENDING', 'SIGNED', 'REJECTED', 'INVALID'));

CREATE UNIQUE INDEX uq_contract_version_primary_document
    ON contract_versions(primary_document_version_id)
    WHERE primary_document_version_id IS NOT NULL;

CREATE UNIQUE INDEX uq_contract_version_signed_document
    ON contract_versions(signed_document_version_id)
    WHERE signed_document_version_id IS NOT NULL;

CREATE TABLE contract_lifecycle_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    contract_id UUID NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
    contract_version_id UUID REFERENCES contract_versions(id) ON DELETE CASCADE,
    action VARCHAR(40) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    from_contract_status VARCHAR(32),
    to_contract_status VARCHAR(32),
    document_version_id UUID REFERENCES document_versions(id),
    comment VARCHAR(1000),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN ('VERSION_CREATED', 'VERSION_FINALIZED', 'SIGNED_FILE_ARCHIVED'))
);

CREATE INDEX idx_contract_lifecycle_contract_time
    ON contract_lifecycle_events(contract_id, occurred_at);

CREATE OR REPLACE FUNCTION prevent_legal_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'legal lifecycle events are append-only';
END;
$$;

CREATE TRIGGER trg_conflict_actions_immutable
BEFORE UPDATE OR DELETE ON conflict_check_actions
FOR EACH ROW EXECUTE FUNCTION prevent_legal_event_mutation();

CREATE TRIGGER trg_contract_lifecycle_immutable
BEFORE UPDATE OR DELETE ON contract_lifecycle_events
FOR EACH ROW EXECUTE FUNCTION prevent_legal_event_mutation();

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('CONFLICT_CHECK_CREATE', '创建利益冲突检查', 'CONFLICT_CHECK', 'CREATE'),
    ('CONFLICT_CHECK_VIEW_ALL', '查看全部利益冲突检查', 'CONFLICT_CHECK', 'VIEW_ALL'),
    ('CONFLICT_CHECK_REVIEW', '复核利益冲突检查', 'CONFLICT_CHECK', 'REVIEW'),
    ('CONTRACT_CREATE', '创建合同', 'CONTRACT', 'CREATE'),
    ('CONTRACT_MANAGE', '管理合同与版本', 'CONTRACT', 'MANAGE'),
    ('CONTRACT_FINALIZE', '确认合同定稿版本', 'CONTRACT', 'FINALIZE'),
    ('CONTRACT_SIGN_ARCHIVE', '归档合同签署件', 'CONTRACT', 'SIGN_ARCHIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE
    (r.code IN ('ADMIN', 'MANAGING_PARTNER')
        AND p.code IN (
            'CONFLICT_CHECK_CREATE', 'CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW',
            'CONTRACT_CREATE', 'CONTRACT_MANAGE', 'CONTRACT_FINALIZE',
            'CONTRACT_SIGN_ARCHIVE'
        ))
    OR (r.code = 'CONFLICT_REVIEWER'
        AND p.code IN ('CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW'))
    OR (r.code IN ('LAWYER', 'ASSISTANT')
        AND p.code = 'CONFLICT_CHECK_CREATE')
    OR (r.code = 'LAWYER'
        AND p.code IN ('CONTRACT_CREATE', 'CONTRACT_MANAGE'))
    OR (r.code = 'CONTRACT_REVIEWER'
        AND p.code IN ('CONTRACT_MANAGE', 'CONTRACT_FINALIZE'))
    OR (r.code = 'SEAL_CUSTODIAN'
        AND p.code = 'CONTRACT_SIGN_ARCHIVE')
ON CONFLICT DO NOTHING;
