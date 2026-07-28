CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    parent_id UUID REFERENCES departments(id),
    dingtalk_department_id VARCHAR(100),
    name VARCHAR(200) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id, dingtalk_department_id)
);

CREATE INDEX idx_departments_org ON departments(organization_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_departments_parent ON departments(parent_id) WHERE deleted_at IS NULL;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    username VARCHAR(100) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    mobile_masked VARCHAR(32),
    email VARCHAR(200),
    dingtalk_user_id VARCHAR(100),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id, username),
    UNIQUE (organization_id, dingtalk_user_id)
);

CREATE INDEX idx_users_org_status ON users(organization_id, status) WHERE deleted_at IS NULL;

CREATE TABLE department_members (
    department_id UUID NOT NULL REFERENCES departments(id),
    user_id UUID NOT NULL REFERENCES users(id),
    is_manager BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (department_id, user_id)
);

CREATE INDEX idx_department_members_user ON department_members(user_id);

CREATE TABLE roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code)
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(150) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    action VARCHAR(80) NOT NULL
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX idx_role_permissions_permission ON role_permissions(permission_id);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role ON user_roles(role_id);

CREATE TABLE data_scope_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(100) NOT NULL,
    name VARCHAR(150) NOT NULL,
    scope_type VARCHAR(50) NOT NULL,
    definition JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (organization_id, code)
);

CREATE TABLE role_data_scopes (
    role_id UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    data_scope_rule_id UUID NOT NULL REFERENCES data_scope_rules(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, data_scope_rule_id)
);

CREATE INDEX idx_role_data_scopes_scope ON role_data_scopes(data_scope_rule_id);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID,
    result VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    ip_address INET,
    user_agent VARCHAR(500),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_org_time ON audit_logs(organization_id, occurred_at DESC);
CREATE INDEX idx_audit_resource ON audit_logs(resource_type, resource_id, occurred_at DESC);
CREATE INDEX idx_audit_actor ON audit_logs(actor_user_id, occurred_at DESC);

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    idempotency_key VARCHAR(150) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expiry ON idempotency_keys(expires_at);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_dispatch ON outbox_events(status, available_at);

