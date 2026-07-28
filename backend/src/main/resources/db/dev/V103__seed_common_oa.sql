INSERT INTO department_members (department_id, user_id, is_manager)
VALUES
    ('00000000-0000-0000-0001-000000000001', '00000000-0000-0000-0002-000000000001', TRUE),
    ('00000000-0000-0000-0001-000000000002', '00000000-0000-0000-0002-000000000002', TRUE),
    ('00000000-0000-0000-0001-000000000002', '00000000-0000-0000-0002-000000000003', FALSE)
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT '00000000-0000-0000-0002-000000000001', id
FROM roles
WHERE organization_id = '00000000-0000-0000-0000-000000000001'
  AND code IN ('HR', 'FINANCE')
ON CONFLICT DO NOTHING;

INSERT INTO meeting_rooms
    (id, organization_id, name, location, capacity, facilities)
VALUES
    ('00000000-0000-0000-0010-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '衡平会议室', '12F 东区', 12, '["电视会议", "无线投屏", "白板"]'::jsonb),
    ('00000000-0000-0000-0010-000000000002',
     '00000000-0000-0000-0000-000000000001',
     '法槌洽谈室', '12F 西区', 6, '["保密电话", "白板"]'::jsonb)
ON CONFLICT (organization_id, name) DO NOTHING;

INSERT INTO announcements
    (id, organization_id, title, summary, content, category, priority,
     status, audience_type, publisher_user_id, published_at)
VALUES
    ('00000000-0000-0000-0011-000000000001',
     '00000000-0000-0000-0000-000000000001',
     '案件文件命名与归档规范更新',
     '从本周起统一使用“案件编号-文件类型-日期-版本”命名。',
     '为保证检索与长期归档质量，请所有项目组从本周起统一使用“案件编号-文件类型-日期-版本”命名。历史文件无需批量改名，新上传文件请按新规范执行。',
     'POLICY', 'IMPORTANT', 'PUBLISHED', 'ALL',
     '00000000-0000-0000-0002-000000000001', now())
ON CONFLICT (id) DO NOTHING;
