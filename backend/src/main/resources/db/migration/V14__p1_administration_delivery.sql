CREATE TABLE workflow_assignment_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID REFERENCES offices(id),
    business_type VARCHAR(64) NOT NULL,
    amount_threshold NUMERIC(18, 2),
    currency CHAR(3),
    assignee_role_code VARCHAR(100),
    fallback_user_id UUID REFERENCES users(id),
    reminder_minutes INTEGER NOT NULL DEFAULT 1440,
    escalation_minutes INTEGER NOT NULL DEFAULT 4320,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 100,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (reminder_minutes BETWEEN 5 AND 525600),
    CHECK (escalation_minutes >= reminder_minutes)
);

CREATE INDEX idx_workflow_assignment_rules
    ON workflow_assignment_rules(organization_id, business_type, office_id, priority);

CREATE TABLE integration_registry (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    integration_type VARCHAR(32) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DISCONNECTED',
    credential_reference VARCHAR(500),
    endpoint_reference VARCHAR(500),
    last_health_status VARCHAR(24),
    last_health_message VARCHAR(500),
    last_checked_at TIMESTAMPTZ,
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, integration_type),
    CHECK (integration_type IN
        ('DINGTALK', 'STORAGE', 'SCANNER', 'OCR', 'ESIGN', 'MAIL', 'CALENDAR', 'FAPIAO', 'ACCOUNTING')),
    CHECK (status IN ('DISCONNECTED', 'CONFIGURED', 'ACTIVE', 'ERROR'))
);

CREATE TABLE data_import_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    import_type VARCHAR(64) NOT NULL,
    original_filename VARCHAR(300) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'VALIDATING',
    total_rows INTEGER NOT NULL DEFAULT 0,
    valid_rows INTEGER NOT NULL DEFAULT 0,
    error_rows INTEGER NOT NULL DEFAULT 0,
    applied_rows INTEGER NOT NULL DEFAULT 0,
    object_key VARCHAR(1000),
    error_report_object_key VARCHAR(1000),
    requested_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK (status IN ('VALIDATING', 'VALIDATED', 'APPLYING', 'COMPLETED', 'FAILED')),
    CHECK (total_rows >= 0 AND valid_rows >= 0 AND error_rows >= 0 AND applied_rows >= 0)
);

CREATE TABLE data_import_errors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    import_job_id UUID NOT NULL REFERENCES data_import_jobs(id) ON DELETE CASCADE,
    row_number INTEGER NOT NULL,
    field_name VARCHAR(100),
    error_code VARCHAR(100) NOT NULL,
    error_message VARCHAR(500) NOT NULL,
    rejected_value VARCHAR(1000),
    CHECK (row_number > 0)
);

CREATE TABLE release_rehearsals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    release_version VARCHAR(80) NOT NULL,
    rehearsal_type VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    executed_by UUID NOT NULL REFERENCES users(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK (rehearsal_type IN ('INITIALIZATION', 'UPGRADE', 'MIGRATION', 'ROLLBACK')),
    CHECK (status IN ('RUNNING', 'PASSED', 'FAILED'))
);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('ADMIN_CONSOLE_VIEW', '查看管理控制台', 'ADMIN_CONSOLE', 'VIEW'),
    ('USER_ACCESS_MANAGE', '管理用户权限', 'USER_ACCESS', 'MANAGE'),
    ('WORKFLOW_CONFIG_MANAGE', '管理流程配置', 'WORKFLOW_CONFIG', 'MANAGE'),
    ('INTEGRATION_HEALTH_VIEW', '查看集成健康', 'INTEGRATION', 'HEALTH_VIEW'),
    ('DATA_IMPORT_MANAGE', '管理数据导入', 'DATA_IMPORT', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN ('ADMIN_CONSOLE_VIEW', 'USER_ACCESS_MANAGE',
                 'WORKFLOW_CONFIG_MANAGE', 'INTEGRATION_HEALTH_VIEW',
                 'DATA_IMPORT_MANAGE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('MANAGING_PARTNER', 'OFFICE_ADMIN')
  AND p.code IN ('ADMIN_CONSOLE_VIEW', 'WORKFLOW_CONFIG_MANAGE',
                 'INTEGRATION_HEALTH_VIEW')
ON CONFLICT DO NOTHING;
