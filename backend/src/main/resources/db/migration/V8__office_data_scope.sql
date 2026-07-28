ALTER TABLE user_offices
    ADD COLUMN access_level VARCHAR(32) NOT NULL DEFAULT 'MEMBER',
    ADD CONSTRAINT user_offices_access_level_check
        CHECK (access_level IN ('MEMBER', 'MANAGER'));

ALTER TABLE announcements ADD COLUMN office_id UUID REFERENCES offices(id);
ALTER TABLE work_tasks ADD COLUMN office_id UUID REFERENCES offices(id);
ALTER TABLE leave_requests ADD COLUMN office_id UUID REFERENCES offices(id);
ALTER TABLE expense_claims ADD COLUMN office_id UUID REFERENCES offices(id);
ALTER TABLE meeting_rooms ADD COLUMN office_id UUID REFERENCES offices(id);

UPDATE work_tasks t
SET office_id = COALESCE(
    (
        SELECT m.office_id
        FROM matters m
        WHERE t.related_business_type = 'MATTER'
          AND m.id = t.related_business_id
    ),
    (SELECT u.primary_office_id FROM users u WHERE u.id = t.owner_user_id)
)
WHERE t.office_id IS NULL;

UPDATE leave_requests l
SET office_id = u.primary_office_id
FROM users u
WHERE u.id = l.applicant_user_id
  AND l.office_id IS NULL;

UPDATE expense_claims e
SET office_id = COALESCE(
    (SELECT m.office_id FROM matters m WHERE m.id = e.matter_id),
    (SELECT u.primary_office_id FROM users u WHERE u.id = e.applicant_user_id)
)
WHERE e.office_id IS NULL;

UPDATE meeting_rooms r
SET office_id = (
    SELECT o.id
    FROM offices o
    WHERE o.organization_id = r.organization_id
      AND o.status = 'ACTIVE'
    ORDER BY o.code
    LIMIT 1
)
WHERE r.office_id IS NULL;

ALTER TABLE meeting_rooms DROP CONSTRAINT meeting_rooms_organization_id_name_key;
CREATE UNIQUE INDEX uq_meeting_rooms_org_office_name
    ON meeting_rooms(organization_id, office_id, name)
    WHERE office_id IS NOT NULL;
CREATE UNIQUE INDEX uq_meeting_rooms_org_legacy_name
    ON meeting_rooms(organization_id, name)
    WHERE office_id IS NULL;

CREATE INDEX idx_announcements_office_status
    ON announcements(organization_id, office_id, status, published_at DESC);
CREATE INDEX idx_work_tasks_office_status
    ON work_tasks(organization_id, office_id, status, due_at);
CREATE INDEX idx_leave_office_status
    ON leave_requests(organization_id, office_id, status, start_at);
CREATE INDEX idx_expense_office_status
    ON expense_claims(organization_id, office_id, status, created_at DESC);
CREATE INDEX idx_meeting_rooms_office
    ON meeting_rooms(organization_id, office_id, status);

INSERT INTO roles (organization_id, code, name)
SELECT id, 'OFFICE_ADMIN', '办公室管理员'
FROM organizations
ON CONFLICT (organization_id, code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'OFFICE_ADMIN'
  AND p.code IN (
      'ANNOUNCEMENT_MANAGE', 'TASK_VIEW_ALL', 'LEAVE_VIEW_ALL',
      'EXPENSE_VIEW_ALL', 'EXPENSE_PAY', 'MEETING_ROOM_MANAGE',
      'WORKFLOW_VIEW_ALL', 'AUDIT_VIEW'
  )
ON CONFLICT DO NOTHING;
