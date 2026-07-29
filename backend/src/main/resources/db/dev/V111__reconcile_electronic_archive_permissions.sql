INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.organization_id = '00000000-0000-0000-0000-000000000001'
  AND (
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
  )
ON CONFLICT DO NOTHING;
