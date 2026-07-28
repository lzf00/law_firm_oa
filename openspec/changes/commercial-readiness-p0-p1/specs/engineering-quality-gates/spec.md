## ADDED Requirements

### Requirement: Isolated automated tests
Backend and frontend tests SHALL be order-independent, SHALL avoid production data, and SHALL mock or fake external providers.

#### Scenario: Run a test alone
- **WHEN** any unit or integration test is executed independently
- **THEN** it produces the same result as in the full suite

### Requirement: Changed-code coverage
New or materially changed business and security packages SHALL meet the configured line and branch coverage thresholds, and repository coverage SHALL not decrease.

#### Scenario: Untested authorization branch
- **WHEN** a change adds an uncovered authorization decision path
- **THEN** the quality gate fails

### Requirement: Security and supply-chain checks
CI SHALL run secret detection, dependency vulnerability scan, SAST, production-image scan, SBOM generation, and license-policy checks.

#### Scenario: Prohibited dependency
- **WHEN** a build contains a critical vulnerability or prohibited license without an approved exception
- **THEN** release promotion fails

### Requirement: Browser acceptance
Critical workflows SHALL be tested in a real headless browser for both locales and supported desktop/mobile viewports with console and network error capture.

#### Scenario: Visible action does nothing
- **WHEN** a critical enabled action fails to open a form, navigate, or submit as specified
- **THEN** browser acceptance fails

### Requirement: Operational acceptance
Release validation SHALL test production configuration rendering, health/readiness, migration, backup, restore, job execution, metrics, and rollback instructions.

#### Scenario: Backup reports success without recoverability
- **WHEN** a backup artifact cannot pass restore verification
- **THEN** operational acceptance fails regardless of backup command exit status
