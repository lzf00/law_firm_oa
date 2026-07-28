ALTER TABLE user_offices
    ADD COLUMN valid_until TIMESTAMPTZ,
    ADD COLUMN assigned_by UUID REFERENCES users(id),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_user_offices_active
    ON user_offices(user_id, office_id, valid_until);

CREATE TABLE office_membership_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID NOT NULL REFERENCES offices(id),
    user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(32) NOT NULL,
    access_level VARCHAR(32),
    is_primary BOOLEAN,
    valid_until TIMESTAMPTZ,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN ('ASSIGNED', 'UPDATED', 'REVOKED')),
    CHECK (access_level IS NULL OR access_level IN ('MEMBER', 'MANAGER'))
);

CREATE INDEX idx_office_membership_history_office
    ON office_membership_history(office_id, occurred_at DESC);
CREATE INDEX idx_office_membership_history_user
    ON office_membership_history(user_id, occurred_at DESC);

CREATE TABLE document_security_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    document_id UUID REFERENCES documents(id),
    document_version_id UUID REFERENCES document_versions(id),
    user_id UUID NOT NULL REFERENCES users(id),
    event_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    download_count INTEGER NOT NULL,
    window_seconds INTEGER NOT NULL,
    ip_address INET,
    user_agent VARCHAR(500),
    correlation_id VARCHAR(100),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (event_type IN ('DOWNLOAD_BURST_WARNING', 'DOWNLOAD_RATE_LIMITED')),
    CHECK (severity IN ('MEDIUM', 'HIGH')),
    CHECK (status IN ('OPEN', 'REVIEWED', 'CLOSED')),
    CHECK (download_count >= 0 AND window_seconds > 0)
);

CREATE INDEX idx_document_security_events_org
    ON document_security_events(organization_id, occurred_at DESC);
CREATE INDEX idx_document_security_events_user
    ON document_security_events(user_id, occurred_at DESC);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('OFFICE_MEMBER_MANAGE', '管理办公室成员', 'OFFICE_MEMBER', 'MANAGE'),
    ('DOCUMENT_SECURITY_VIEW', '查看文档安全事件', 'DOCUMENT_SECURITY', 'VIEW')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('ADMIN', 'MANAGING_PARTNER')
  AND p.code IN ('OFFICE_MEMBER_MANAGE', 'DOCUMENT_SECURITY_VIEW')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'OFFICE_ADMIN'
  AND p.code = 'OFFICE_MEMBER_MANAGE'
ON CONFLICT DO NOTHING;
