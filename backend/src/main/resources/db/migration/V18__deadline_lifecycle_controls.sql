ALTER TABLE deadlines
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN source_reference VARCHAR(500),
    ADD COLUMN calculation_note TEXT,
    ADD COLUMN completed_by UUID REFERENCES users(id),
    ADD COLUMN completion_note VARCHAR(1000),
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by UUID REFERENCES users(id),
    ADD COLUMN cancellation_reason VARCHAR(1000),
    ADD COLUMN version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE deadlines
    ADD CONSTRAINT deadlines_priority_check
    CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    ADD CONSTRAINT deadlines_type_check
    CHECK (deadline_type IN (
        'COURT', 'COURT_DEADLINE', 'ARBITRATION', 'FILING',
        'INTERNAL', 'INTERNAL_TASK', 'OTHER'
    )),
    ADD CONSTRAINT deadlines_source_check
    CHECK (source_type IN ('COURT_ORDER', 'STATUTE', 'CLIENT', 'INTERNAL', 'OTHER'));

CREATE TABLE deadline_lifecycle_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    deadline_id UUID NOT NULL REFERENCES deadlines(id),
    action VARCHAR(32) NOT NULL,
    actor_user_id UUID REFERENCES users(id),
    actor_display_name VARCHAR(200) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    previous_due_at TIMESTAMPTZ,
    next_due_at TIMESTAMPTZ,
    previous_owner_user_id UUID REFERENCES users(id),
    next_owner_user_id UUID REFERENCES users(id),
    note VARCHAR(1000),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN (
        'CREATED', 'UPDATED', 'OVERDUE_MARKED', 'COMPLETED', 'CANCELLED', 'REOPENED'
    ))
);

CREATE INDEX idx_deadline_lifecycle_deadline_time
    ON deadline_lifecycle_events(deadline_id, occurred_at);

INSERT INTO deadline_lifecycle_events
    (organization_id, deadline_id, action,
     actor_user_id, actor_display_name,
     from_status, to_status,
     next_due_at, next_owner_user_id, note, occurred_at)
SELECT m.organization_id, d.id, 'CREATED',
       d.created_by, creator.display_name,
       NULL, d.status,
       d.due_at, d.owner_user_id,
       'Legacy deadline imported into lifecycle controls', d.created_at
FROM deadlines d
JOIN matters m ON m.id = d.matter_id
JOIN users creator ON creator.id = d.created_by;

CREATE TRIGGER trg_deadline_lifecycle_immutable
BEFORE UPDATE OR DELETE ON deadline_lifecycle_events
FOR EACH ROW EXECUTE FUNCTION prevent_legal_event_mutation();

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('DEADLINE_VIEW_ALL', '查看数据范围内全部期限', 'DEADLINE', 'VIEW_ALL'),
    ('DEADLINE_MANAGE', '管理数据范围内期限', 'DEADLINE', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE
    (r.code IN ('ADMIN', 'MANAGING_PARTNER')
        AND p.code IN ('DEADLINE_VIEW_ALL', 'DEADLINE_MANAGE'))
    OR (r.code = 'OFFICE_ADMIN'
        AND p.code IN ('DEADLINE_VIEW_ALL', 'DEADLINE_MANAGE'))
ON CONFLICT DO NOTHING;
