ALTER TABLE work_tasks
    ADD COLUMN completed_by UUID REFERENCES users(id),
    ADD COLUMN completion_note VARCHAR(1000),
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN cancelled_by UUID REFERENCES users(id),
    ADD COLUMN cancellation_reason VARCHAR(1000);

CREATE TABLE work_task_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    task_id UUID NOT NULL REFERENCES work_tasks(id) ON DELETE CASCADE,
    action VARCHAR(32) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    note VARCHAR(1000),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN (
        'CREATED', 'UPDATED', 'STATUS_CHANGED', 'STATUS_CONFIRMED',
        'COMPLETED', 'CANCELLED', 'REOPENED', 'COMMENTED'
    ))
);

CREATE INDEX idx_work_task_events_task_time
    ON work_task_events(task_id, occurred_at DESC);
CREATE INDEX idx_work_task_events_org_time
    ON work_task_events(organization_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION reject_work_task_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'work task lifecycle events are append-only';
END;
$$;

CREATE TRIGGER trg_work_task_events_immutable_update
BEFORE UPDATE ON work_task_events
FOR EACH ROW EXECUTE FUNCTION reject_work_task_event_mutation();

CREATE TRIGGER trg_work_task_events_immutable_delete
BEFORE DELETE ON work_task_events
FOR EACH ROW EXECUTE FUNCTION reject_work_task_event_mutation();

INSERT INTO work_task_events
    (organization_id, task_id, action, actor_user_id, to_status, note, occurred_at)
SELECT t.organization_id, t.id, 'CREATED', t.assigner_user_id, t.status,
       'Lifecycle history initialized', t.created_at
FROM work_tasks t
WHERE NOT EXISTS (
    SELECT 1 FROM work_task_events e
    WHERE e.task_id = t.id AND e.action = 'CREATED'
);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('TASK_CREATE', '创建协作任务', 'WORK_TASK', 'CREATE'),
    ('TASK_MANAGE', '管理全部协作任务', 'WORK_TASK', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE (
    r.code IN ('ADMIN', 'MANAGING_PARTNER', 'LAWYER', 'ASSISTANT')
    AND p.code = 'TASK_CREATE'
) OR (
    r.code IN ('ADMIN', 'MANAGING_PARTNER', 'OFFICE_ADMIN')
    AND p.code = 'TASK_MANAGE'
)
ON CONFLICT DO NOTHING;
