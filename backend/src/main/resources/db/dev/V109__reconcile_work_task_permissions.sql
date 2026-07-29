INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.organization_id = '00000000-0000-0000-0000-000000000001'
  AND (
    (r.code IN ('ADMIN', 'MANAGING_PARTNER', 'LAWYER', 'ASSISTANT')
      AND p.code = 'TASK_CREATE')
    OR
    (r.code IN ('ADMIN', 'MANAGING_PARTNER', 'OFFICE_ADMIN')
      AND p.code = 'TASK_MANAGE')
  )
ON CONFLICT DO NOTHING;

INSERT INTO work_tasks
    (id, organization_id, title, description, status, priority,
     assigner_user_id, owner_user_id, related_business_type,
     related_business_id, due_at, office_id)
VALUES
    ('00000000-0000-0000-0020-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '复核财产保全证据目录',
     '核对证据编号、页码与原件保管位置，完成后在评论中留下复核结论。',
     'IN_PROGRESS', 'HIGH',
     '00000000-0000-0000-0002-000000000001',
     '00000000-0000-0000-0002-000000000002',
     'MATTER', '00000000-0000-0000-0006-000000000001',
     now() + interval '3 days',
     (SELECT office_id FROM matters WHERE id = '00000000-0000-0000-0006-000000000001')),
    ('00000000-0000-0000-0020-000000000002',
     '00000000-0000-0000-0000-000000000001',
     '整理庭前会议问题清单',
     '汇总争议焦点、证人问题与客户待确认事项。',
     'TODO', 'NORMAL',
     '00000000-0000-0000-0002-000000000002',
     '00000000-0000-0000-0002-000000000003',
     'MATTER', '00000000-0000-0000-0006-000000000001',
     now() + interval '6 days',
     (SELECT office_id FROM matters WHERE id = '00000000-0000-0000-0006-000000000001'))
ON CONFLICT (id) DO NOTHING;

INSERT INTO work_task_participants (task_id, user_id, participant_role)
VALUES
    ('00000000-0000-0000-0020-000000000001',
     '00000000-0000-0000-0002-000000000003', 'COLLABORATOR')
ON CONFLICT DO NOTHING;

INSERT INTO work_task_events
    (organization_id, task_id, action, actor_user_id, to_status, note, occurred_at)
SELECT t.organization_id, t.id, 'CREATED', t.assigner_user_id, t.status,
       'Development fixture created', t.created_at
FROM work_tasks t
WHERE t.id IN (
    '00000000-0000-0000-0020-000000000001',
    '00000000-0000-0000-0020-000000000002'
)
AND NOT EXISTS (
    SELECT 1 FROM work_task_events e
    WHERE e.task_id = t.id AND e.action = 'CREATED'
);
