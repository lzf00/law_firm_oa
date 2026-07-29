INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.organization_id = '00000000-0000-0000-0000-000000000001'
  AND (
      (r.code IN ('ADMIN', 'MANAGING_PARTNER')
          AND p.code IN ('DEADLINE_VIEW_ALL', 'DEADLINE_MANAGE'))
      OR (r.code = 'OFFICE_ADMIN'
          AND p.code IN ('DEADLINE_VIEW_ALL', 'DEADLINE_MANAGE'))
  )
ON CONFLICT DO NOTHING;

INSERT INTO deadline_lifecycle_events
    (organization_id, deadline_id, action,
     actor_user_id, actor_display_name,
     from_status, to_status,
     next_due_at, next_owner_user_id, note, occurred_at)
SELECT m.organization_id, d.id, 'CREATED',
       d.created_by, creator.display_name,
       NULL, d.status,
       d.due_at, d.owner_user_id,
       'Development seed imported into lifecycle controls', d.created_at
FROM deadlines d
JOIN matters m ON m.id = d.matter_id
JOIN users creator ON creator.id = d.created_by
WHERE NOT EXISTS (
    SELECT 1
    FROM deadline_lifecycle_events e
    WHERE e.deadline_id = d.id
);
