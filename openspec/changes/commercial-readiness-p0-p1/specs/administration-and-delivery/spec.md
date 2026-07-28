## ADDED Requirements

### Requirement: Administration center
Authorized administrators SHALL manage organization branding, offices, users, office memberships, roles, permissions, workflow assignments, integration state, and security settings.

#### Scenario: Non-admin opens administration
- **WHEN** a user without the required permission requests an administration page or API
- **THEN** access is denied and sensitive configuration is not returned

### Requirement: Safe organization synchronization
Directory synchronization SHALL provide dry-run differences, organization-bound apply, role/office mapping, deactivation policy, idempotency, and an audit summary.

#### Scenario: Synchronization would deactivate a privileged user
- **WHEN** a sync plan would deactivate the last active administrator
- **THEN** apply is blocked pending explicit remediation

### Requirement: Configurable workflow assignments
Administrators SHALL configure eligible approvers, escalation timing, reminders, and fallback assignments without editing BPMN source for routine changes.

#### Scenario: No eligible approver
- **WHEN** a workflow starts without any active eligible approver
- **THEN** creation fails safely with an actionable configuration error

### Requirement: Customer initialization and migration
The product SHALL support validated, organization-bound import templates and bounded exports for users, offices, parties, matters, contracts, deadlines, and opening balances.

#### Scenario: Import validation failure
- **WHEN** an import contains invalid references or records for another organization
- **THEN** no partial business write occurs and a row-level error report is produced

### Requirement: Integration health
Administrators SHALL see non-secret health, last success, last failure, and required configuration for DingTalk, storage, scanner, OCR, e-sign, mail, calendar, and invoice adapters.

#### Scenario: Provider credential missing
- **WHEN** an optional provider is not configured
- **THEN** its feature is disabled and the administration page identifies the missing resource without exposing secrets
