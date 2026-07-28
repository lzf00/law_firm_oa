CREATE TABLE parties (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    party_type VARCHAR(32) NOT NULL,
    normalized_name VARCHAR(300) NOT NULL,
    display_name VARCHAR(300) NOT NULL,
    unified_social_credit_code VARCHAR(64),
    identity_number_ciphertext TEXT,
    contact_ciphertext JSONB NOT NULL DEFAULT '{}'::jsonb,
    risk_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    notes TEXT,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CHECK (party_type IN ('PERSON', 'ORGANIZATION', 'GOVERNMENT', 'OTHER'))
);

CREATE INDEX idx_parties_org_name ON parties(organization_id, normalized_name) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_parties_credit_code
    ON parties(organization_id, unified_social_credit_code)
    WHERE unified_social_credit_code IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE party_aliases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id UUID NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
    alias_name VARCHAR(300) NOT NULL,
    normalized_alias VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (party_id, normalized_alias)
);

CREATE INDEX idx_party_alias_lookup ON party_aliases(normalized_alias);

CREATE TABLE party_relations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_party_id UUID NOT NULL REFERENCES parties(id),
    target_party_id UUID NOT NULL REFERENCES parties(id),
    relation_type VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    valid_from DATE,
    valid_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source_party_id <> target_party_id)
);

CREATE INDEX idx_party_rel_source ON party_relations(source_party_id);
CREATE INDEX idx_party_rel_target ON party_relations(target_party_id);

CREATE TABLE clients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    party_id UUID NOT NULL UNIQUE REFERENCES parties(id),
    client_number VARCHAR(60) NOT NULL,
    source VARCHAR(100),
    owner_user_id UUID REFERENCES users(id),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_clients_number ON clients(client_number) WHERE deleted_at IS NULL;
CREATE INDEX idx_clients_owner ON clients(owner_user_id) WHERE deleted_at IS NULL;

CREATE TABLE matters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    matter_number VARCHAR(80) NOT NULL,
    title VARCHAR(300) NOT NULL,
    matter_type VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'INTAKE',
    confidentiality_level VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
    responsible_user_id UUID NOT NULL REFERENCES users(id),
    opened_at DATE,
    closed_at DATE,
    court_name VARCHAR(300),
    case_number VARCHAR(150),
    description TEXT,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (organization_id, matter_number),
    CHECK (status IN ('INTAKE', 'CONFLICT_REVIEW', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'ARCHIVED')),
    CHECK (confidentiality_level IN ('INTERNAL', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'))
);

CREATE INDEX idx_matters_org_status ON matters(organization_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_matters_responsible ON matters(responsible_user_id, status) WHERE deleted_at IS NULL;

CREATE TABLE matter_members (
    matter_id UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id),
    member_role VARCHAR(32) NOT NULL,
    can_download BOOLEAN NOT NULL DEFAULT FALSE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    PRIMARY KEY (matter_id, user_id),
    CHECK (member_role IN ('RESPONSIBLE', 'LEAD', 'COUNSEL', 'ASSISTANT', 'OBSERVER'))
);

CREATE INDEX idx_matter_members_user ON matter_members(user_id, left_at);

CREATE TABLE matter_clients (
    matter_id UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    client_id UUID NOT NULL REFERENCES clients(id),
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (matter_id, client_id)
);

CREATE INDEX idx_matter_clients_client ON matter_clients(client_id);

CREATE TABLE matter_parties (
    matter_id UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    party_id UUID NOT NULL REFERENCES parties(id),
    party_role VARCHAR(80) NOT NULL,
    side VARCHAR(32) NOT NULL,
    notes VARCHAR(500),
    PRIMARY KEY (matter_id, party_id, party_role)
);

CREATE INDEX idx_matter_parties_party ON matter_parties(party_id);

CREATE TABLE conflict_checks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    request_number VARCHAR(80) NOT NULL,
    proposed_client_id UUID REFERENCES parties(id),
    proposed_matter_title VARCHAR(300) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    risk_level VARCHAR(32),
    decision VARCHAR(32),
    requested_by UUID NOT NULL REFERENCES users(id),
    reviewed_by UUID REFERENCES users(id),
    review_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    UNIQUE (organization_id, request_number),
    CHECK (status IN ('DRAFT', 'SUBMITTED', 'REVIEWING', 'APPROVED', 'REJECTED')),
    CHECK (decision IS NULL OR decision IN ('CLEAR', 'WAIVER_REQUIRED', 'REJECT'))
);

CREATE INDEX idx_conflict_org_status ON conflict_checks(organization_id, status, created_at DESC);

CREATE TABLE conflict_check_parties (
    conflict_check_id UUID NOT NULL REFERENCES conflict_checks(id) ON DELETE CASCADE,
    party_id UUID NOT NULL REFERENCES parties(id),
    proposed_role VARCHAR(80) NOT NULL,
    PRIMARY KEY (conflict_check_id, party_id, proposed_role)
);

CREATE INDEX idx_conflict_party_lookup ON conflict_check_parties(party_id);

CREATE TABLE matter_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matter_id UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    event_type VARCHAR(80) NOT NULL,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    event_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_matter_events_timeline ON matter_events(matter_id, event_at DESC);

CREATE TABLE deadlines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matter_id UUID NOT NULL REFERENCES matters(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    deadline_type VARCHAR(80) NOT NULL,
    priority VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    owner_user_id UUID NOT NULL REFERENCES users(id),
    reminder_policy JSONB NOT NULL DEFAULT '{"daysBefore":[7,3,1]}'::jsonb,
    completed_at TIMESTAMPTZ,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status IN ('OPEN', 'COMPLETED', 'CANCELLED', 'OVERDUE'))
);

CREATE INDEX idx_deadlines_owner_due ON deadlines(owner_user_id, status, due_at);
CREATE INDEX idx_deadlines_matter ON deadlines(matter_id, due_at);

