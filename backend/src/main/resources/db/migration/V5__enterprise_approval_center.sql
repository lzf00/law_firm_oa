ALTER TABLE matters DROP CONSTRAINT IF EXISTS matters_status_check;
ALTER TABLE matters
    ADD CONSTRAINT matters_status_check
    CHECK (status IN (
        'INTAKE', 'CONFLICT_REVIEW', 'ACTIVE', 'SUSPENDED',
        'CLOSED', 'ARCHIVED', 'REJECTED'
    ));

ALTER TABLE contracts DROP CONSTRAINT IF EXISTS contracts_status_check;
ALTER TABLE contracts
    ADD CONSTRAINT contracts_status_check
    CHECK (status IN (
        'DRAFT', 'REVIEWING', 'APPROVED', 'SIGNED', 'EXPIRED',
        'TERMINATED', 'ARCHIVED', 'REJECTED'
    ));

ALTER TABLE seal_requests
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE workflow_links
    ADD COLUMN decision VARCHAR(32),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_workflow_one_running_business
    ON workflow_links(organization_id, business_type, business_id)
    WHERE status = 'RUNNING';

CREATE INDEX idx_workflow_org_status
    ON workflow_links(organization_id, status, started_at DESC);

CREATE TABLE workflow_action_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    workflow_link_id UUID NOT NULL REFERENCES workflow_links(id) ON DELETE CASCADE,
    task_id VARCHAR(100),
    task_name VARCHAR(200),
    action VARCHAR(32) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    comment VARCHAR(500),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN ('START', 'CLAIM', 'APPROVE', 'REJECT', 'TRANSFER', 'CANCEL', 'REMIND'))
);

CREATE INDEX idx_workflow_actions_link_time
    ON workflow_action_logs(workflow_link_id, occurred_at);
CREATE INDEX idx_workflow_actions_actor_time
    ON workflow_action_logs(actor_user_id, occurred_at DESC);

CREATE TABLE approval_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    workflow_link_id UUID NOT NULL REFERENCES workflow_links(id) ON DELETE CASCADE,
    flowable_task_id VARCHAR(100) NOT NULL UNIQUE,
    task_definition_key VARCHAR(150),
    task_name VARCHAR(200) NOT NULL,
    assignee_user_id UUID REFERENCES users(id),
    candidate_group VARCHAR(100),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    completed_by UUID REFERENCES users(id),
    completed_at TIMESTAMPTZ,
    decision VARCHAR(32),
    comment VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'TRANSFERRED', 'CANCELLED'))
);

CREATE INDEX idx_approval_tasks_org_status
    ON approval_tasks(organization_id, status, created_at DESC);
CREATE INDEX idx_approval_tasks_assignee
    ON approval_tasks(assignee_user_id, status, created_at DESC);
CREATE INDEX idx_approval_tasks_group
    ON approval_tasks(candidate_group, status, created_at DESC);

CREATE TABLE notification_inbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notification_type VARCHAR(80) NOT NULL,
    title VARCHAR(300) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    resource_type VARCHAR(100),
    resource_id UUID,
    action_url VARCHAR(500),
    priority VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(32) NOT NULL DEFAULT 'UNREAD',
    deduplication_key VARCHAR(200) NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (recipient_user_id, deduplication_key),
    CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CHECK (status IN ('UNREAD', 'READ', 'ARCHIVED'))
);

CREATE INDEX idx_notification_recipient_status
    ON notification_inbox(recipient_user_id, status, created_at DESC);

ALTER TABLE outbox_events
    ADD COLUMN organization_id UUID REFERENCES organizations(id),
    ADD COLUMN last_error VARCHAR(1000),
    ADD COLUMN locked_at TIMESTAMPTZ,
    ADD COLUMN correlation_id VARCHAR(100);

CREATE INDEX idx_outbox_org_status
    ON outbox_events(organization_id, status, available_at);

CREATE TABLE organization_sync_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    provider VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    departments_seen INTEGER NOT NULL DEFAULT 0,
    users_seen INTEGER NOT NULL DEFAULT 0,
    departments_changed INTEGER NOT NULL DEFAULT 0,
    users_changed INTEGER NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    requested_by UUID REFERENCES users(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK (provider IN ('DINGTALK', 'FEISHU', 'MANUAL')),
    CHECK (mode IN ('FULL', 'INCREMENTAL', 'SNAPSHOT')),
    CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_org_sync_runs_org_time
    ON organization_sync_runs(organization_id, started_at DESC);

CREATE TABLE external_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    external_user_id VARCHAR(150) NOT NULL,
    external_open_id VARCHAR(150),
    external_union_id VARCHAR(150),
    raw_profile JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, provider, external_user_id),
    UNIQUE (user_id, provider)
);

CREATE INDEX idx_external_identity_union
    ON external_identities(provider, external_union_id)
    WHERE external_union_id IS NOT NULL;

CREATE TABLE external_departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    external_department_id VARCHAR(150) NOT NULL,
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, provider, external_department_id),
    UNIQUE (department_id, provider)
);

CREATE INDEX idx_external_department_lookup
    ON external_departments(provider, external_department_id);

CREATE TABLE deadline_reminder_dispatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deadline_id UUID NOT NULL REFERENCES deadlines(id) ON DELETE CASCADE,
    recipient_user_id UUID NOT NULL REFERENCES users(id),
    reminder_date DATE NOT NULL,
    days_before INTEGER NOT NULL,
    outbox_event_id UUID REFERENCES outbox_events(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (deadline_id, recipient_user_id, reminder_date, days_before)
);

CREATE INDEX idx_deadline_reminder_date
    ON deadline_reminder_dispatches(reminder_date);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('WORKFLOW_APPROVE', '处理审批', 'WORKFLOW', 'APPROVE'),
    ('WORKFLOW_VIEW_ALL', '查看全部审批', 'WORKFLOW', 'VIEW_ALL'),
    ('AUDIT_VIEW', '查看审计日志', 'AUDIT', 'VIEW'),
    ('ORGANIZATION_SYNC', '同步组织通讯录', 'ORGANIZATION', 'SYNC'),
    ('NOTIFICATION_MANAGE', '管理通知', 'NOTIFICATION', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN (
      'WORKFLOW_APPROVE', 'WORKFLOW_VIEW_ALL', 'AUDIT_VIEW',
      'ORGANIZATION_SYNC', 'NOTIFICATION_MANAGE'
  )
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'MANAGING_PARTNER'
  AND p.code IN ('WORKFLOW_APPROVE', 'WORKFLOW_VIEW_ALL', 'AUDIT_VIEW')
ON CONFLICT DO NOTHING;
