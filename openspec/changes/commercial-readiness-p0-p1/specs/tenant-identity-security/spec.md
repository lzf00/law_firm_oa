## ADDED Requirements

### Requirement: Organization-bound session subject
The system SHALL bind every production session to one active user ID and one active organization ID, and SHALL reject sessions whose user no longer belongs to that organization.

#### Scenario: Resolve a valid session
- **WHEN** a request carries a valid unexpired session for an active organization user
- **THEN** the request actor contains the exact user and organization stored in the session

#### Scenario: Reject a legacy or ambiguous session
- **WHEN** a session contains only a username or resolves to a different organization
- **THEN** the system rejects the request as unauthenticated and records a security event

### Requirement: Explicit tenant configuration
Production tenant configuration MUST be selected by an explicit trusted organization or host mapping and MUST NOT default to the first organization row.

#### Scenario: Unknown production host
- **WHEN** a public tenant-config request arrives for a host with no configured tenant
- **THEN** the system returns a bounded not-configured response without exposing another organization

### Requirement: Organization-bound DingTalk exchange
The DingTalk login state and user exchange SHALL bind the requested organization and SHALL match the external user only within that organization.

#### Scenario: External ID exists in another organization
- **WHEN** a DingTalk external ID is valid but belongs only to another organization
- **THEN** login is denied without revealing the other organization or user

### Requirement: Browser session protection
The production browser session SHALL use Secure, HttpOnly, SameSite cookies and SHALL support logout and expiry without storing the access credential in Web Storage.

#### Scenario: Browser JavaScript inspects storage
- **WHEN** a signed-in user opens the application
- **THEN** no reusable production session credential is present in localStorage or sessionStorage

### Requirement: Tenant isolation regression suite
Authorization tests MUST create at least two organizations with overlapping usernames and external IDs and verify cross-organization denial for every sensitive domain.

#### Scenario: Cross-organization record access
- **WHEN** a user from organization A requests a record owned only by organization B
- **THEN** the system denies access and does not reveal record metadata
