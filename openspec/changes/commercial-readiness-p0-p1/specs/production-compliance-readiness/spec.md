## ADDED Requirements

### Requirement: Reproducible production release
Each release SHALL have immutable version metadata, validated configuration, database migration status, health checks, deployment instructions, and a tested rollback path.

#### Scenario: Release preflight fails
- **WHEN** a required secret, tenant identifier, storage, Redis security setting, or health dependency is missing
- **THEN** production startup or release promotion fails with a non-secret diagnostic

### Requirement: Transport and service security
Production SHALL terminate TLS at a trusted boundary, enforce secure headers, protect Redis and object storage with authentication and network controls, and rate-limit authentication and high-risk APIs.

#### Scenario: Repeated login attempts
- **WHEN** an identity or source exceeds the configured login threshold
- **THEN** requests are throttled and a security metric/event is emitted

### Requirement: Monitoring, backup, and recovery
The product SHALL expose service, job, database-pool, upload-scan, notification, and security metrics and SHALL perform scheduled encrypted backups with automated restore verification.

#### Scenario: Restore drill
- **WHEN** the restore verification job runs against the latest eligible backup
- **THEN** schema integrity and required business-table checks pass without changing production data

### Requirement: Privacy and data rights
The delivery package SHALL include privacy notice, processing inventory, rights-request workflow, retention rules, complaint channel, and records for impact assessments and security incidents.

#### Scenario: Authorized deletion request
- **WHEN** an approved deletion request is not blocked by legal duty or hold
- **THEN** the system executes or schedules deletion and retains the minimum auditable decision record

### Requirement: Cross-border decision control
Cross-border storage or transfer SHALL require a recorded purpose, data categories, destination, legal basis, protection measures, and approval before activation.

#### Scenario: Unapproved overseas integration
- **WHEN** an integration would send protected data outside the approved residency boundary
- **THEN** the integration remains disabled

### Requirement: Commercial delivery documents
Every customer release SHALL include versioned SLA, DPA template, support policy, incident runbook, release notes, migration guide, SBOM, and third-party notices.

#### Scenario: Missing release artifact
- **WHEN** a required commercial or compliance artifact is absent
- **THEN** the release cannot be marked commercially ready
