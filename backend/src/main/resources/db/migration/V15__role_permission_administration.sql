INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('ROLE_PERMISSION_MANAGE', '管理角色权限', 'ROLE_PERMISSION', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code = 'ROLE_PERMISSION_MANAGE'
ON CONFLICT DO NOTHING;
