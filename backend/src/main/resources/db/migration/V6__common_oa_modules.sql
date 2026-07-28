CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE announcements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    title VARCHAR(300) NOT NULL,
    summary VARCHAR(500),
    content TEXT NOT NULL,
    category VARCHAR(80) NOT NULL DEFAULT 'NOTICE',
    priority VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    audience_type VARCHAR(32) NOT NULL DEFAULT 'ALL',
    publisher_user_id UUID NOT NULL REFERENCES users(id),
    published_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,
    CHECK (priority IN ('NORMAL', 'IMPORTANT', 'URGENT')),
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CHECK (audience_type IN ('ALL', 'DEPARTMENT', 'USERS')),
    CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE TABLE announcement_targets (
    announcement_id UUID NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    target_type VARCHAR(32) NOT NULL,
    target_id UUID NOT NULL,
    PRIMARY KEY (announcement_id, target_type, target_id),
    CHECK (target_type IN ('DEPARTMENT', 'USER'))
);

CREATE TABLE announcement_reads (
    announcement_id UUID NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (announcement_id, user_id)
);

CREATE INDEX idx_announcements_org_status
    ON announcements(organization_id, status, published_at DESC);
CREATE INDEX idx_announcement_reads_user
    ON announcement_reads(user_id, read_at DESC);

CREATE TABLE work_tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    title VARCHAR(300) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'TODO',
    priority VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    assigner_user_id UUID NOT NULL REFERENCES users(id),
    owner_user_id UUID NOT NULL REFERENCES users(id),
    related_business_type VARCHAR(80),
    related_business_id UUID,
    due_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,
    CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT'))
);

CREATE TABLE work_task_participants (
    task_id UUID NOT NULL REFERENCES work_tasks(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    participant_role VARCHAR(32) NOT NULL DEFAULT 'WATCHER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, user_id),
    CHECK (participant_role IN ('COLLABORATOR', 'WATCHER'))
);

CREATE TABLE work_task_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES work_tasks(id) ON DELETE CASCADE,
    author_user_id UUID NOT NULL REFERENCES users(id),
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_work_tasks_owner_status
    ON work_tasks(owner_user_id, status, due_at);
CREATE INDEX idx_work_tasks_assigner
    ON work_tasks(assigner_user_id, created_at DESC);
CREATE INDEX idx_work_task_comments_task
    ON work_task_comments(task_id, created_at);

CREATE TABLE leave_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    request_number VARCHAR(80) NOT NULL,
    applicant_user_id UUID NOT NULL REFERENCES users(id),
    leave_type VARCHAR(32) NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    duration_hours NUMERIC(8,2) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    emergency_contact VARCHAR(200),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (organization_id, request_number),
    CHECK (leave_type IN ('ANNUAL', 'PERSONAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'OTHER')),
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CHECK (end_at > start_at),
    CHECK (duration_hours > 0)
);

ALTER TABLE leave_requests
    ADD CONSTRAINT leave_requests_no_overlap
    EXCLUDE USING gist (
        applicant_user_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    )
    WHERE (status IN ('SUBMITTED', 'APPROVED'));

CREATE INDEX idx_leave_org_applicant
    ON leave_requests(organization_id, applicant_user_id, created_at DESC);
CREATE INDEX idx_leave_org_status
    ON leave_requests(organization_id, status, start_at);

CREATE TABLE expense_claims (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    claim_number VARCHAR(80) NOT NULL,
    applicant_user_id UUID NOT NULL REFERENCES users(id),
    matter_id UUID REFERENCES matters(id),
    title VARCHAR(300) NOT NULL,
    purpose VARCHAR(1000) NOT NULL,
    total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    paid_at TIMESTAMPTZ,
    paid_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,
    UNIQUE (organization_id, claim_number),
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'PAID', 'CANCELLED')),
    CHECK (total_amount >= 0)
);

CREATE TABLE expense_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    expense_claim_id UUID NOT NULL REFERENCES expense_claims(id) ON DELETE CASCADE,
    category VARCHAR(80) NOT NULL,
    occurred_on DATE NOT NULL,
    description VARCHAR(500) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    receipt_document_id UUID REFERENCES documents(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (amount > 0)
);

CREATE INDEX idx_expense_org_applicant
    ON expense_claims(organization_id, applicant_user_id, created_at DESC);
CREATE INDEX idx_expense_org_status
    ON expense_claims(organization_id, status, created_at DESC);
CREATE INDEX idx_expense_items_claim
    ON expense_items(expense_claim_id);

CREATE TABLE meeting_rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    name VARCHAR(150) NOT NULL,
    location VARCHAR(300) NOT NULL,
    capacity INTEGER NOT NULL,
    facilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, name),
    CHECK (capacity > 0),
    CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE meeting_bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    meeting_room_id UUID NOT NULL REFERENCES meeting_rooms(id),
    organizer_user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(300) NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    attendee_count INTEGER NOT NULL DEFAULT 1,
    attendee_user_ids UUID[] NOT NULL DEFAULT '{}',
    notes VARCHAR(1000),
    status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version INTEGER NOT NULL DEFAULT 0,
    CHECK (end_at > start_at),
    CHECK (attendee_count > 0),
    CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);

ALTER TABLE meeting_bookings
    ADD CONSTRAINT meeting_bookings_no_overlap
    EXCLUDE USING gist (
        meeting_room_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    )
    WHERE (status = 'CONFIRMED');

CREATE INDEX idx_meeting_bookings_org_time
    ON meeting_bookings(organization_id, start_at, end_at);
CREATE INDEX idx_meeting_bookings_organizer
    ON meeting_bookings(organizer_user_id, start_at DESC);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('ANNOUNCEMENT_MANAGE', '管理公告', 'ANNOUNCEMENT', 'MANAGE'),
    ('TASK_VIEW_ALL', '查看全部协作任务', 'WORK_TASK', 'VIEW_ALL'),
    ('LEAVE_VIEW_ALL', '查看全部请假', 'LEAVE_REQUEST', 'VIEW_ALL'),
    ('EXPENSE_VIEW_ALL', '查看全部报销', 'EXPENSE_CLAIM', 'VIEW_ALL'),
    ('EXPENSE_PAY', '登记报销付款', 'EXPENSE_CLAIM', 'PAY'),
    ('MEETING_ROOM_MANAGE', '管理会议室', 'MEETING_ROOM', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO roles (organization_id, code, name)
SELECT id, 'HR', '人力资源' FROM organizations
ON CONFLICT (organization_id, code) DO NOTHING;

INSERT INTO roles (organization_id, code, name)
SELECT id, 'FINANCE', '财务' FROM organizations
ON CONFLICT (organization_id, code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN (
      'ANNOUNCEMENT_MANAGE', 'TASK_VIEW_ALL', 'LEAVE_VIEW_ALL',
      'EXPENSE_VIEW_ALL', 'EXPENSE_PAY', 'MEETING_ROOM_MANAGE'
  )
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'MANAGING_PARTNER'
  AND p.code IN ('TASK_VIEW_ALL', 'LEAVE_VIEW_ALL', 'EXPENSE_VIEW_ALL')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE (r.code = 'HR' AND p.code = 'LEAVE_VIEW_ALL')
   OR (r.code = 'FINANCE' AND p.code IN ('EXPENSE_VIEW_ALL', 'EXPENSE_PAY'))
   OR (r.code = 'ADMINISTRATION' AND p.code IN ('ANNOUNCEMENT_MANAGE', 'MEETING_ROOM_MANAGE'))
ON CONFLICT DO NOTHING;
