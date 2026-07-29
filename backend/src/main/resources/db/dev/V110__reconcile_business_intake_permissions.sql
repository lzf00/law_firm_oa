INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.organization_id = '00000000-0000-0000-0000-000000000001'
  AND (
    (r.code IN ('ADMIN', 'MANAGING_PARTNER', 'OFFICE_ADMIN', 'LAWYER', 'ASSISTANT')
      AND p.code = 'PARTY_CREATE')
    OR
    (r.code IN ('ADMIN', 'MANAGING_PARTNER')
      AND p.code = 'PARTY_MANAGE')
    OR
    (r.code IN ('ADMIN', 'MANAGING_PARTNER', 'OFFICE_ADMIN', 'LAWYER')
      AND p.code IN ('CLIENT_CREATE', 'MATTER_CREATE'))
    OR
    (r.code IN ('ADMIN', 'MANAGING_PARTNER')
      AND p.code IN ('CLIENT_MANAGE', 'MATTER_MANAGE'))
  )
ON CONFLICT DO NOTHING;
