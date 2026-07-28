CREATE TABLE engagements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID NOT NULL REFERENCES offices(id),
    matter_id UUID NOT NULL REFERENCES matters(id),
    client_id UUID REFERENCES clients(id),
    engagement_number VARCHAR(80) NOT NULL,
    title VARCHAR(300) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    effective_from DATE NOT NULL,
    effective_to DATE,
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, engagement_number),
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CLOSED')),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

CREATE UNIQUE INDEX uq_engagement_active_matter
    ON engagements(matter_id) WHERE status = 'APPROVED';

CREATE TABLE fee_terms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    engagement_id UUID NOT NULL REFERENCES engagements(id) ON DELETE CASCADE,
    fee_type VARCHAR(32) NOT NULL,
    rate_amount NUMERIC(18, 2),
    cap_amount NUMERIC(18, 2),
    contingency_percent NUMERIC(7, 4),
    tax_rate NUMERIC(7, 4) NOT NULL DEFAULT 0,
    effective_from DATE NOT NULL,
    effective_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (fee_type IN ('HOURLY', 'FIXED', 'RETAINER', 'CONTINGENCY', 'HYBRID')),
    CHECK (rate_amount IS NULL OR rate_amount >= 0),
    CHECK (cap_amount IS NULL OR cap_amount >= 0),
    CHECK (contingency_percent IS NULL OR contingency_percent BETWEEN 0 AND 100),
    CHECK (tax_rate BETWEEN 0 AND 100)
);

CREATE TABLE time_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID NOT NULL REFERENCES offices(id),
    matter_id UUID NOT NULL REFERENCES matters(id),
    engagement_id UUID REFERENCES engagements(id),
    professional_user_id UUID NOT NULL REFERENCES users(id),
    work_date DATE NOT NULL,
    minutes INTEGER NOT NULL,
    description VARCHAR(2000) NOT NULL,
    billable BOOLEAN NOT NULL DEFAULT TRUE,
    currency CHAR(3) NOT NULL,
    rate_snapshot NUMERIC(18, 2) NOT NULL DEFAULT 0,
    amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    approved_by UUID REFERENCES users(id),
    approved_at TIMESTAMPTZ,
    invoice_line_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (minutes BETWEEN 1 AND 1440),
    CHECK (rate_snapshot >= 0 AND amount >= 0),
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'INVOICED'))
);

CREATE INDEX idx_time_entries_reporting
    ON time_entries(organization_id, office_id, work_date, status);
CREATE INDEX idx_time_entries_matter ON time_entries(matter_id, work_date DESC);

CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID NOT NULL REFERENCES offices(id),
    engagement_id UUID NOT NULL REFERENCES engagements(id),
    matter_id UUID NOT NULL REFERENCES matters(id),
    invoice_number VARCHAR(80),
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    subtotal NUMERIC(18, 2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    paid_amount NUMERIC(18, 2) NOT NULL DEFAULT 0,
    due_date DATE,
    issued_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(1000),
    source_invoice_id UUID REFERENCES invoices(id),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, invoice_number),
    CHECK (status IN ('DRAFT', 'UNDER_REVIEW', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED', 'ADJUSTMENT')),
    CHECK (subtotal >= 0 AND tax_amount >= 0 AND total_amount >= 0 AND paid_amount >= 0)
);

CREATE INDEX idx_invoices_receivable
    ON invoices(organization_id, office_id, status, due_date);

CREATE TABLE invoice_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    line_number INTEGER NOT NULL,
    line_type VARCHAR(24) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    quantity NUMERIC(18, 4) NOT NULL,
    unit_price NUMERIC(18, 2) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    source_time_entry_id UUID REFERENCES time_entries(id),
    professional_user_id UUID REFERENCES users(id),
    work_date DATE,
    rate_snapshot NUMERIC(18, 2),
    immutable_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (invoice_id, line_number),
    CHECK (line_type IN ('TIME', 'FIXED_FEE', 'EXPENSE', 'ADJUSTMENT')),
    CHECK (quantity >= 0 AND unit_price >= 0)
);

ALTER TABLE time_entries
    ADD CONSTRAINT fk_time_entry_invoice_line
    FOREIGN KEY (invoice_line_id) REFERENCES invoice_lines(id);

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    office_id UUID NOT NULL REFERENCES offices(id),
    payment_reference VARCHAR(120) NOT NULL,
    received_on DATE NOT NULL,
    payer_name VARCHAR(300) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    unallocated_amount NUMERIC(18, 2) NOT NULL,
    method VARCHAR(32) NOT NULL,
    bank_reference VARCHAR(200),
    status VARCHAR(24) NOT NULL DEFAULT 'POSTED',
    recorded_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, payment_reference),
    CHECK (amount > 0 AND unallocated_amount >= 0),
    CHECK (status IN ('POSTED', 'REVERSED'))
);

CREATE TABLE payment_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    amount NUMERIC(18, 2) NOT NULL,
    allocated_by UUID NOT NULL REFERENCES users(id),
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (amount > 0)
);

CREATE TABLE collection_activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    invoice_id UUID NOT NULL REFERENCES invoices(id),
    activity_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    notes VARCHAR(2000) NOT NULL,
    next_action_at TIMESTAMPTZ,
    owner_user_id UUID NOT NULL REFERENCES users(id),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (activity_type IN ('CALL', 'EMAIL', 'LETTER', 'MEETING', 'PROMISE_TO_PAY', 'DISPUTE'))
);

CREATE TABLE finance_provider_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    provider_type VARCHAR(32) NOT NULL,
    provider_code VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DISCONNECTED',
    credential_reference VARCHAR(500),
    last_health_status VARCHAR(24),
    last_checked_at TIMESTAMPTZ,
    updated_by UUID NOT NULL REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id, provider_type),
    CHECK (provider_type IN ('FAPIAO', 'ACCOUNTING')),
    CHECK (status IN ('DISCONNECTED', 'CONFIGURED', 'ACTIVE', 'ERROR'))
);

INSERT INTO permissions (code, name, resource_type, action)
VALUES
    ('FINANCE_VIEW', '查看律所财务', 'FINANCE', 'VIEW'),
    ('FINANCE_MANAGE', '管理律所财务', 'FINANCE', 'MANAGE'),
    ('TIME_ENTRY_CREATE', '登记工时', 'TIME_ENTRY', 'CREATE'),
    ('TIME_ENTRY_APPROVE', '审批工时', 'TIME_ENTRY', 'APPROVE'),
    ('INVOICE_MANAGE', '管理账单', 'INVOICE', 'MANAGE'),
    ('PAYMENT_MANAGE', '管理回款', 'PAYMENT', 'MANAGE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('ADMIN', 'MANAGING_PARTNER', 'FINANCE')
  AND p.code IN ('FINANCE_VIEW', 'FINANCE_MANAGE', 'TIME_ENTRY_APPROVE',
                 'INVOICE_MANAGE', 'PAYMENT_MANAGE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('ADMIN', 'MANAGING_PARTNER', 'PARTNER', 'LAWYER', 'PARALEGAL')
  AND p.code = 'TIME_ENTRY_CREATE'
ON CONFLICT DO NOTHING;
