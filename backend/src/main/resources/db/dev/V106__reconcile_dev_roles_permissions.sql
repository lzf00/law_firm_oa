-- Production migrations run before the development organization is seeded.
-- Reconcile organization-derived roles and grants after all development fixtures exist.
INSERT INTO roles (organization_id, code, name)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'HR', '人力资源'),
    ('00000000-0000-0000-0000-000000000001', 'FINANCE', '财务'),
    ('00000000-0000-0000-0000-000000000001', 'OFFICE_ADMIN', '办公室管理员'),
    ('00000000-0000-0000-0000-000000000001', 'RECORDS_MANAGER', '档案管理员')
ON CONFLICT (organization_id, code) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT '00000000-0000-0000-0002-000000000001', id
FROM roles
WHERE organization_id = '00000000-0000-0000-0000-000000000001'
  AND code IN ('HR', 'FINANCE')
ON CONFLICT DO NOTHING;

-- The development administrator is intentionally omnipotent so acceptance
-- tests exercise features rather than provisioning order.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.organization_id = '00000000-0000-0000-0000-000000000001'
  AND r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.organization_id = '00000000-0000-0000-0000-000000000001'
  AND (
      (r.code = 'MANAGING_PARTNER' AND p.code IN (
          'WORKFLOW_APPROVE', 'WORKFLOW_VIEW_ALL', 'AUDIT_VIEW',
          'TASK_VIEW_ALL', 'LEAVE_VIEW_ALL', 'EXPENSE_VIEW_ALL',
          'OFFICE_MEMBER_MANAGE', 'DOCUMENT_SECURITY_VIEW',
          'DOCUMENT_GOVERNANCE_VIEW', 'DOCUMENT_GOVERNANCE_MANAGE',
          'DOCUMENT_EXPORT', 'DOCUMENT_DELETE', 'LEGAL_HOLD_MANAGE',
          'FINANCE_VIEW', 'FINANCE_MANAGE', 'TIME_ENTRY_CREATE',
          'TIME_ENTRY_APPROVE', 'INVOICE_MANAGE', 'PAYMENT_MANAGE',
          'ADMIN_CONSOLE_VIEW', 'WORKFLOW_CONFIG_MANAGE',
          'INTEGRATION_HEALTH_VIEW',
          'CONFLICT_CHECK_CREATE', 'CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW',
          'CONTRACT_CREATE', 'CONTRACT_MANAGE', 'CONTRACT_FINALIZE',
          'CONTRACT_SIGN_ARCHIVE'
      ))
      OR (r.code = 'LAWYER' AND p.code IN (
          'TIME_ENTRY_CREATE', 'CONFLICT_CHECK_CREATE',
          'CONTRACT_CREATE', 'CONTRACT_MANAGE'
      ))
      OR (r.code = 'ASSISTANT' AND p.code = 'CONFLICT_CHECK_CREATE')
      OR (r.code = 'CONFLICT_REVIEWER' AND p.code IN (
          'CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW'
      ))
      OR (r.code = 'CONTRACT_REVIEWER' AND p.code IN (
          'CONTRACT_MANAGE', 'CONTRACT_FINALIZE'
      ))
      OR (r.code = 'SEAL_CUSTODIAN' AND p.code = 'CONTRACT_SIGN_ARCHIVE')
      OR (r.code = 'HR' AND p.code = 'LEAVE_VIEW_ALL')
      OR (r.code = 'FINANCE' AND p.code IN (
          'EXPENSE_VIEW_ALL', 'EXPENSE_PAY', 'FINANCE_VIEW',
          'FINANCE_MANAGE', 'TIME_ENTRY_APPROVE',
          'INVOICE_MANAGE', 'PAYMENT_MANAGE'
      ))
      OR (r.code = 'ADMINISTRATION'
          AND p.code IN ('ANNOUNCEMENT_MANAGE', 'MEETING_ROOM_MANAGE'))
      OR (r.code = 'OFFICE_ADMIN' AND p.code IN (
          'ANNOUNCEMENT_MANAGE', 'TASK_VIEW_ALL', 'LEAVE_VIEW_ALL',
          'EXPENSE_VIEW_ALL', 'EXPENSE_PAY', 'MEETING_ROOM_MANAGE',
          'WORKFLOW_VIEW_ALL', 'AUDIT_VIEW', 'OFFICE_MEMBER_MANAGE',
          'ADMIN_CONSOLE_VIEW', 'WORKFLOW_CONFIG_MANAGE',
          'INTEGRATION_HEALTH_VIEW'
      ))
      OR (r.code = 'RECORDS_MANAGER' AND p.code IN (
          'DOCUMENT_GOVERNANCE_VIEW', 'DOCUMENT_GOVERNANCE_MANAGE',
          'DOCUMENT_EXPORT', 'DOCUMENT_DELETE', 'LEGAL_HOLD_MANAGE'
      ))
  )
ON CONFLICT DO NOTHING;
