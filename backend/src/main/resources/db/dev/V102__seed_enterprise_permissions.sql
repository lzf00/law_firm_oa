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
