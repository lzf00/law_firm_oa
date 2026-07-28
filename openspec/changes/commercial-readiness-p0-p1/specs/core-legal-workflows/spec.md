## ADDED Requirements

### Requirement: Complete visible actions
Every enabled business action displayed in the application SHALL perform an authorized operation or open a usable form; no enabled control may be a no-op.

#### Scenario: Create from a list page
- **WHEN** an authorized user activates New Party, New Contract, or New Deadline
- **THEN** a localized validated form opens and successful submission updates the list

### Requirement: Matter-centered detail
The system SHALL provide an authorized matter detail workspace containing parties, conflicts, team, contracts, deadlines, documents, approvals, activity, and archive state.

#### Scenario: Open a matter
- **WHEN** an authorized user selects a matter from the matter list
- **THEN** the user sees only the related modules and actions allowed by matter and office scope

### Requirement: Complete approval actions
Authorized approvers SHALL be able to approve, reject with a reason, transfer to an eligible user, and send a rate-limited reminder.

#### Scenario: Reject an approval
- **WHEN** an authorized approver supplies a non-empty rejection reason
- **THEN** the workflow and business record reflect rejection and all participants receive an audited notification

### Requirement: Actionable notifications
The shell SHALL display unread notifications, allow marking them read, and deep-link to an authorized target.

#### Scenario: Open an unauthorized notification target
- **WHEN** a notification references a record the current user can no longer access
- **THEN** the target remains hidden and the application shows a localized access message

### Requirement: Predictable list behavior
Every business list SHALL expose server-side pagination, allow-listed sorting, relevant search/filter fields, and clear loading, empty, and error states.

#### Scenario: Request an excessive page size
- **WHEN** a client requests more than the configured maximum page size
- **THEN** the server applies the maximum and returns consistent pagination metadata
