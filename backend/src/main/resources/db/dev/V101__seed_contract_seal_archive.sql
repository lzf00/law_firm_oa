INSERT INTO contracts
    (id, organization_id, contract_number, title, client_id,
     responsible_user_id, status, effective_date, expiry_date,
     amount, currency, created_by)
VALUES
    ('00000000-0000-0000-0007-000000000001', '00000000-0000-0000-0000-000000000001',
     'HT-2026-001', '华辰科技常年法律顾问合同',
     '00000000-0000-0000-0005-000000000001',
     '00000000-0000-0000-0002-000000000002', 'REVIEWING',
     CURRENT_DATE, CURRENT_DATE + 365, 180000, 'CNY',
     '00000000-0000-0000-0002-000000000001');

INSERT INTO contract_members (contract_id, user_id, member_role, can_download)
VALUES
    ('00000000-0000-0000-0007-000000000001',
     '00000000-0000-0000-0002-000000000001', 'COUNSEL', TRUE),
    ('00000000-0000-0000-0007-000000000001',
     '00000000-0000-0000-0002-000000000002', 'RESPONSIBLE', TRUE);

INSERT INTO contract_matters (contract_id, matter_id)
VALUES
    ('00000000-0000-0000-0007-000000000001',
     '00000000-0000-0000-0006-000000000001');

INSERT INTO seals
    (id, organization_id, name, seal_type, custodian_user_id)
VALUES
    ('00000000-0000-0000-0008-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '明理律师事务所公章', 'OFFICIAL',
     '00000000-0000-0000-0002-000000000001'),
    ('00000000-0000-0000-0008-000000000002',
     '00000000-0000-0000-0000-000000000001',
     '合同专用章', 'CONTRACT',
     '00000000-0000-0000-0002-000000000001');

INSERT INTO archive_volumes
    (id, organization_id, archive_number, title,
     retention_policy_code, created_by)
VALUES
    ('00000000-0000-0000-0009-000000000001',
     '00000000-0000-0000-0000-000000000001',
     'AJ-2026-001', '华辰科技采购合同纠纷卷宗',
     'LITIGATION_10Y', '00000000-0000-0000-0002-000000000001');
