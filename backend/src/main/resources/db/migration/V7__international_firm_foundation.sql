CREATE TABLE organization_settings (
    organization_id UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    brand_name_zh VARCHAR(200) NOT NULL,
    brand_name_en VARCHAR(200) NOT NULL,
    short_name_zh VARCHAR(80) NOT NULL,
    short_name_en VARCHAR(80) NOT NULL,
    default_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    supported_locales VARCHAR(16)[] NOT NULL DEFAULT ARRAY['zh-CN', 'en-US'],
    primary_timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai',
    base_currency CHAR(3) NOT NULL DEFAULT 'CNY',
    website_url VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (default_locale = ANY(supported_locales))
);

CREATE TABLE offices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code VARCHAR(50) NOT NULL,
    name_zh VARCHAR(150) NOT NULL,
    name_en VARCHAR(150) NOT NULL,
    country_code CHAR(2) NOT NULL,
    city_zh VARCHAR(100) NOT NULL,
    city_en VARCHAR(100) NOT NULL,
    timezone VARCHAR(80) NOT NULL,
    default_currency CHAR(3) NOT NULL,
    address_zh VARCHAR(500),
    address_en VARCHAR(500),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code),
    CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_offices_org_status
    ON offices(organization_id, status, code);

ALTER TABLE departments ADD COLUMN office_id UUID REFERENCES offices(id);
ALTER TABLE users
    ADD COLUMN preferred_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    ADD COLUMN primary_office_id UUID REFERENCES offices(id),
    ADD CONSTRAINT users_preferred_locale_check
        CHECK (preferred_locale IN ('zh-CN', 'en-US'));

CREATE TABLE user_offices (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    office_id UUID NOT NULL REFERENCES offices(id) ON DELETE CASCADE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, office_id)
);

CREATE UNIQUE INDEX uq_user_primary_office
    ON user_offices(user_id) WHERE is_primary;
CREATE INDEX idx_user_offices_office ON user_offices(office_id, user_id);

ALTER TABLE matters
    ADD COLUMN office_id UUID REFERENCES offices(id),
    ADD COLUMN country_code CHAR(2),
    ADD COLUMN jurisdiction VARCHAR(200),
    ADD COLUMN working_language VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    ADD COLUMN billing_currency CHAR(3) NOT NULL DEFAULT 'CNY',
    ADD CONSTRAINT matters_working_language_check
        CHECK (working_language IN ('zh-CN', 'en-US', 'ar')),
    ADD CONSTRAINT matters_country_code_check
        CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$');

CREATE INDEX idx_matters_office_status
    ON matters(organization_id, office_id, status, updated_at DESC);
CREATE INDEX idx_matters_country
    ON matters(organization_id, country_code, status);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('OFFICE_MANAGE', '管理全球办公室', 'OFFICE', 'MANAGE'),
    ('TENANT_BRAND_MANAGE', '管理律所品牌', 'ORGANIZATION', 'BRAND_MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'ADMIN'
  AND p.code IN ('OFFICE_MANAGE', 'TENANT_BRAND_MANAGE')
ON CONFLICT DO NOTHING;
