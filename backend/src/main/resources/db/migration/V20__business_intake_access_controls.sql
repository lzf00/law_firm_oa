INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('PARTY_CREATE', '创建利益相关主体', 'PARTY', 'CREATE'),
    ('PARTY_MANAGE', '管理利益相关主体', 'PARTY', 'MANAGE'),
    ('CLIENT_CREATE', '登记客户', 'CLIENT', 'CREATE'),
    ('CLIENT_MANAGE', '管理客户档案', 'CLIENT', 'MANAGE'),
    ('MATTER_CREATE', '创建案件', 'MATTER', 'CREATE'),
    ('MATTER_MANAGE', '管理全部案件', 'MATTER', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE (
    r.code IN ('ADMIN', 'MANAGING_PARTNER', 'OFFICE_ADMIN', 'LAWYER', 'ASSISTANT')
    AND p.code = 'PARTY_CREATE'
) OR (
    r.code IN ('ADMIN', 'MANAGING_PARTNER')
    AND p.code = 'PARTY_MANAGE'
) OR (
    r.code IN ('ADMIN', 'MANAGING_PARTNER', 'OFFICE_ADMIN', 'LAWYER')
    AND p.code IN ('CLIENT_CREATE', 'MATTER_CREATE')
) OR (
    r.code IN ('ADMIN', 'MANAGING_PARTNER')
    AND p.code IN ('CLIENT_MANAGE', 'MATTER_MANAGE')
)
ON CONFLICT DO NOTHING;
