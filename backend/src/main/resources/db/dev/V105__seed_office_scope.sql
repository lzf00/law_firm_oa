UPDATE user_offices uo
SET access_level = 'MANAGER'
FROM users u
WHERE u.id = uo.user_id
  AND u.username = 'admin'
  AND u.organization_id = '00000000-0000-0000-0000-000000000001';

UPDATE meeting_rooms
SET office_id = '00000000-0000-0000-0012-000000000001'
WHERE organization_id = '00000000-0000-0000-0000-000000000001'
  AND office_id IS NULL;

UPDATE work_tasks t
SET office_id = COALESCE(
    (
        SELECT m.office_id
        FROM matters m
        WHERE t.related_business_type = 'MATTER'
          AND m.id = t.related_business_id
    ),
    (SELECT u.primary_office_id FROM users u WHERE u.id = t.owner_user_id)
)
WHERE t.organization_id = '00000000-0000-0000-0000-000000000001'
  AND t.office_id IS NULL;

UPDATE leave_requests l
SET office_id = u.primary_office_id
FROM users u
WHERE u.id = l.applicant_user_id
  AND l.organization_id = '00000000-0000-0000-0000-000000000001'
  AND l.office_id IS NULL;

UPDATE expense_claims e
SET office_id = COALESCE(
    (SELECT m.office_id FROM matters m WHERE m.id = e.matter_id),
    (SELECT u.primary_office_id FROM users u WHERE u.id = e.applicant_user_id)
)
WHERE e.organization_id = '00000000-0000-0000-0000-000000000001'
  AND e.office_id IS NULL;
