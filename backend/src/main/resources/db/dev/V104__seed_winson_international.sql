UPDATE organizations
SET name = '文森律师事务所', updated_at = now()
WHERE id = '00000000-0000-0000-0000-000000000001';

INSERT INTO organization_settings (
    organization_id, brand_name_zh, brand_name_en,
    short_name_zh, short_name_en, default_locale,
    supported_locales, primary_timezone, base_currency, website_url
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    '文森律师事务所', 'Winson Partners and Legal Consultants',
    '文森', 'WINSON', 'zh-CN',
    ARRAY['zh-CN', 'en-US'], 'Asia/Dubai', 'AED',
    'https://www.winsonglobal.com/'
)
ON CONFLICT (organization_id) DO UPDATE SET
    brand_name_zh = EXCLUDED.brand_name_zh,
    brand_name_en = EXCLUDED.brand_name_en,
    short_name_zh = EXCLUDED.short_name_zh,
    short_name_en = EXCLUDED.short_name_en,
    default_locale = EXCLUDED.default_locale,
    supported_locales = EXCLUDED.supported_locales,
    primary_timezone = EXCLUDED.primary_timezone,
    base_currency = EXCLUDED.base_currency,
    website_url = EXCLUDED.website_url,
    updated_at = now();

INSERT INTO offices (
    id, organization_id, code, name_zh, name_en, country_code,
    city_zh, city_en, timezone, default_currency, address_en
)
VALUES
    ('00000000-0000-0000-0012-000000000001', '00000000-0000-0000-0000-000000000001',
     'DXB', '迪拜总部', 'Dubai Headquarters', 'AE', '迪拜', 'Dubai',
     'Asia/Dubai', 'AED', 'Office 1002, H Dubai Office Tower, Sheikh Zayed Road, Dubai'),
    ('00000000-0000-0000-0012-000000000002', '00000000-0000-0000-0000-000000000001',
     'RUH', '利雅得办公室', 'Riyadh Office', 'SA', '利雅得', 'Riyadh',
     'Asia/Riyadh', 'SAR', 'Office 601, Mazaya Tower, Olaya Street, Riyadh'),
    ('00000000-0000-0000-0012-000000000003', '00000000-0000-0000-0000-000000000001',
     'CAI', '开罗办公室', 'Cairo Office', 'EG', '开罗', 'Cairo',
     'Africa/Cairo', 'EGP', '15 El-Obour Buildings, Salah Salem Street, Cairo'),
    ('00000000-0000-0000-0012-000000000004', '00000000-0000-0000-0000-000000000001',
     'BGW', '巴格达办公室', 'Baghdad Office', 'IQ', '巴格达', 'Baghdad',
     'Asia/Baghdad', 'IQD', 'Al-Shaab, Baghdad'),
    ('00000000-0000-0000-0012-000000000005', '00000000-0000-0000-0000-000000000001',
     'MCT', '马斯喀特办公室', 'Muscat Office', 'OM', '马斯喀特', 'Muscat',
     'Asia/Muscat', 'OMR', 'The Office Building, Bawshar, Muscat'),
    ('00000000-0000-0000-0012-000000000006', '00000000-0000-0000-0000-000000000001',
     'JNB', '约翰内斯堡办公室', 'Johannesburg Office', 'ZA', '约翰内斯堡', 'Johannesburg',
     'Africa/Johannesburg', 'ZAR', 'Sandton City Office Tower, Johannesburg'),
    ('00000000-0000-0000-0012-000000000007', '00000000-0000-0000-0000-000000000001',
     'BJS', '北京办公室', 'Beijing Office', 'CN', '北京', 'Beijing',
     'Asia/Shanghai', 'CNY', 'Lize Tiandi Office Building, Beijing'),
    ('00000000-0000-0000-0012-000000000008', '00000000-0000-0000-0000-000000000001',
     'SHA', '上海办公室', 'Shanghai Office', 'CN', '上海', 'Shanghai',
     'Asia/Shanghai', 'CNY', 'Pudong New Area, Shanghai'),
    ('00000000-0000-0000-0012-000000000009', '00000000-0000-0000-0000-000000000001',
     'NKG', '南京办公室', 'Nanjing Office', 'CN', '南京', 'Nanjing',
     'Asia/Shanghai', 'CNY', NULL),
    ('00000000-0000-0000-0012-000000000010', '00000000-0000-0000-0000-000000000001',
     'HGH', '杭州办公室', 'Hangzhou Office', 'CN', '杭州', 'Hangzhou',
     'Asia/Shanghai', 'CNY', 'Yinglan Central Office Building, Hangzhou'),
    ('00000000-0000-0000-0012-000000000011', '00000000-0000-0000-0000-000000000001',
     'TAO', '青岛办公室', 'Qingdao Office', 'CN', '青岛', 'Qingdao',
     'Asia/Shanghai', 'CNY', NULL),
    ('00000000-0000-0000-0012-000000000012', '00000000-0000-0000-0000-000000000001',
     'CTU', '成都办公室', 'Chengdu Office', 'CN', '成都', 'Chengdu',
     'Asia/Shanghai', 'CNY', NULL)
ON CONFLICT (organization_id, code) DO NOTHING;

UPDATE users SET primary_office_id =
    CASE username
        WHEN 'admin' THEN '00000000-0000-0000-0012-000000000001'::uuid
        WHEN 'zhanglawyer' THEN '00000000-0000-0000-0012-000000000008'::uuid
        ELSE '00000000-0000-0000-0012-000000000007'::uuid
    END
WHERE organization_id = '00000000-0000-0000-0000-000000000001';

INSERT INTO user_offices (user_id, office_id, is_primary)
SELECT id, primary_office_id, TRUE
FROM users
WHERE organization_id = '00000000-0000-0000-0000-000000000001'
  AND primary_office_id IS NOT NULL
ON CONFLICT (user_id, office_id) DO UPDATE SET is_primary = TRUE;

UPDATE departments SET office_id =
    CASE name
        WHEN '管理合伙人办公室' THEN '00000000-0000-0000-0012-000000000001'::uuid
        WHEN '争议解决部' THEN '00000000-0000-0000-0012-000000000008'::uuid
        ELSE '00000000-0000-0000-0012-000000000007'::uuid
    END
WHERE organization_id = '00000000-0000-0000-0000-000000000001';

UPDATE matters
SET office_id = '00000000-0000-0000-0012-000000000001',
    country_code = 'AE',
    jurisdiction = 'United Arab Emirates / 阿联酋',
    working_language = 'zh-CN',
    billing_currency = 'AED'
WHERE organization_id = '00000000-0000-0000-0000-000000000001'
  AND office_id IS NULL;
