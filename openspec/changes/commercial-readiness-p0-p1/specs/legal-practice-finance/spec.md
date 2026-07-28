## ADDED Requirements

### Requirement: Engagement and fee terms
Authorized users SHALL record engagement status, client, matter, fee arrangement, currency, rate rules, effective dates, and approval evidence.

#### Scenario: Bill without approved engagement
- **WHEN** a user attempts to create a billable invoice line for a matter without effective approved terms
- **THEN** the system blocks posting and identifies the missing prerequisite

### Requirement: Time entry
Professionals SHALL record dated matter activities with duration, narrative, billable state, and an immutable rate/currency snapshot at billing time.

#### Scenario: Submit overlapping time
- **WHEN** a user submits entries that violate configured overlap or daily-hour policy
- **THEN** the system blocks or flags the entries according to firm policy

### Requirement: Invoice lifecycle
Authorized finance users SHALL draft, review, approve, issue, cancel, and adjust invoices using explicit transitions and immutable issued line snapshots.

#### Scenario: Modify an issued invoice
- **WHEN** a user needs to correct an issued invoice
- **THEN** the original remains unchanged and the system creates an auditable adjustment or credit

### Requirement: Collection ledger
The system SHALL record payments, allocation, outstanding balance, due dates, and collection activity without deleting posted financial events.

#### Scenario: Partial payment
- **WHEN** a payment is allocated to an invoice for less than its outstanding amount
- **THEN** the invoice remains partially paid with a reconciled balance

### Requirement: Operational reporting
Authorized users SHALL view matter revenue, unbilled time, work in progress, receivables aging, collection status, and professional utilization within office scope.

#### Scenario: Office-scoped report
- **WHEN** an office-scoped manager opens a finance report
- **THEN** totals include only records visible to that office scope
