-- Keep V106 immutable for installations where it has already been applied.
-- Reconcile legal lifecycle grants after V17 adds the permissions and the
-- development fixtures add organization-specific roles.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.organization_id = '00000000-0000-0000-0000-000000000001'
  AND (
      (r.code = 'ADMIN' AND p.code IN (
          'CONFLICT_CHECK_CREATE', 'CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW',
          'CONTRACT_CREATE', 'CONTRACT_MANAGE', 'CONTRACT_FINALIZE',
          'CONTRACT_SIGN_ARCHIVE'
      ))
      OR (r.code = 'MANAGING_PARTNER' AND p.code IN (
          'CONFLICT_CHECK_CREATE', 'CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW',
          'CONTRACT_CREATE', 'CONTRACT_MANAGE', 'CONTRACT_FINALIZE',
          'CONTRACT_SIGN_ARCHIVE'
      ))
      OR (r.code = 'LAWYER' AND p.code IN (
          'CONFLICT_CHECK_CREATE', 'CONTRACT_CREATE', 'CONTRACT_MANAGE'
      ))
      OR (r.code = 'ASSISTANT' AND p.code = 'CONFLICT_CHECK_CREATE')
      OR (r.code = 'CONFLICT_REVIEWER' AND p.code IN (
          'CONFLICT_CHECK_VIEW_ALL', 'CONFLICT_CHECK_REVIEW'
      ))
      OR (r.code = 'CONTRACT_REVIEWER' AND p.code IN (
          'CONTRACT_MANAGE', 'CONTRACT_FINALIZE'
      ))
      OR (r.code = 'SEAL_CUSTODIAN' AND p.code = 'CONTRACT_SIGN_ARCHIVE')
  )
ON CONFLICT DO NOTHING;
