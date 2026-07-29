ALTER TABLE archive_items
    ADD COLUMN document_version_id UUID REFERENCES document_versions(id);

UPDATE archive_items ai
SET document_version_id = d.current_version_id
FROM documents d
WHERE d.id = ai.document_id
  AND ai.document_version_id IS NULL;

CREATE INDEX idx_archive_items_document_version
    ON archive_items(document_version_id)
    WHERE document_version_id IS NOT NULL;

ALTER TABLE archive_volumes
    ADD COLUMN archived_by UUID REFERENCES users(id);

ALTER TABLE archive_volumes
    ADD CONSTRAINT archive_volumes_status_check
    CHECK (status IN ('OPEN', 'ARCHIVED'));

CREATE TABLE archive_lifecycle_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    archive_volume_id UUID NOT NULL REFERENCES archive_volumes(id) ON DELETE CASCADE,
    action VARCHAR(32) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    document_id UUID REFERENCES documents(id),
    document_version_id UUID REFERENCES document_versions(id),
    comment VARCHAR(1000),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (action IN ('CREATED', 'ITEM_ADDED', 'CLOSED'))
);

CREATE INDEX idx_archive_lifecycle_volume_time
    ON archive_lifecycle_events(archive_volume_id, occurred_at, id);

CREATE TRIGGER trg_archive_lifecycle_immutable
BEFORE UPDATE OR DELETE ON archive_lifecycle_events
FOR EACH ROW EXECUTE FUNCTION prevent_legal_event_mutation();

INSERT INTO archive_lifecycle_events
    (organization_id, archive_volume_id, action, actor_user_id, occurred_at)
SELECT organization_id, id, 'CREATED', created_by, created_at
FROM archive_volumes;

INSERT INTO archive_lifecycle_events
    (organization_id, archive_volume_id, action, actor_user_id, occurred_at)
SELECT organization_id, id, 'CLOSED', COALESCE(archived_by, created_by), archived_at
FROM archive_volumes
WHERE status = 'ARCHIVED' AND archived_at IS NOT NULL;

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('CONTRACT_VIEW_ALL', '查看全部合同', 'CONTRACT', 'VIEW_ALL'),
    ('ARCHIVE_CREATE', '创建电子卷宗', 'ARCHIVE_VOLUME', 'CREATE'),
    ('ARCHIVE_VIEW_ALL', '查看全部电子卷宗', 'ARCHIVE_VOLUME', 'VIEW_ALL'),
    ('ARCHIVE_MANAGE', '管理电子卷宗编目', 'ARCHIVE_VOLUME', 'MANAGE'),
    ('ARCHIVE_CLOSE', '确认电子卷宗封卷', 'ARCHIVE_VOLUME', 'CLOSE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE
    (r.code IN ('ADMIN', 'MANAGING_PARTNER', 'CONTRACT_REVIEWER', 'SEAL_CUSTODIAN')
      AND p.code = 'CONTRACT_VIEW_ALL')
    OR
    (r.code IN ('ADMIN', 'MANAGING_PARTNER')
      AND p.code IN ('ARCHIVE_CREATE', 'ARCHIVE_VIEW_ALL', 'ARCHIVE_MANAGE', 'ARCHIVE_CLOSE'))
    OR
    (r.code = 'LAWYER' AND p.code = 'ARCHIVE_CREATE')
    OR
    (r.code = 'RECORDS_MANAGER'
      AND p.code IN ('ARCHIVE_CREATE', 'ARCHIVE_VIEW_ALL', 'ARCHIVE_MANAGE', 'ARCHIVE_CLOSE'))
ON CONFLICT DO NOTHING;
