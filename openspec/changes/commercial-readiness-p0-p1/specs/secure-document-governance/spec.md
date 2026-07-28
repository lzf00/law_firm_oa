## ADDED Requirements

### Requirement: Quarantined ingestion
Newly uploaded files SHALL remain unavailable until asynchronous security verification succeeds.

#### Scenario: Upload completes
- **WHEN** object upload completion is confirmed
- **THEN** the document enters QUARANTINED or SCANNING and cannot be downloaded or previewed

### Requirement: Content-based validation
The scanner SHALL validate normalized filename, size, allowed extension, detected file signature, archive limits, macro policy, SHA-256, and malware result without trusting client Content-Type alone.

#### Scenario: Extension and signature disagree
- **WHEN** a file named as an allowed document has a disallowed or mismatched signature
- **THEN** the document is rejected, quarantined from users, and recorded in security events

### Requirement: Fail-closed scanner operation
Production SHALL treat scanner unavailability, timeout, or indeterminate results as non-available document states.

#### Scenario: Antivirus unavailable
- **WHEN** the production antivirus adapter cannot complete a scan
- **THEN** the document remains unavailable and operations receive an alert

### Requirement: Upload and object cleanup
A scheduled process SHALL delete expired incomplete uploads and unreferenced quarantine objects after a configurable retention period.

#### Scenario: Expired upload
- **WHEN** an incomplete upload exceeds the retention period and is not under hold
- **THEN** its object and upload record are safely cleaned and the action is measured

### Requirement: Version, preview, search, and retention governance
Authorized users SHALL be able to inspect version history, request safe previews, search indexed text, apply retention rules, and place or release legal holds.

#### Scenario: Retention expires under legal hold
- **WHEN** a document reaches retention expiry while an active legal hold applies
- **THEN** deletion is blocked and the hold reason remains auditable

### Requirement: Audited document access
Every preview, download, export, scan-state override, hold change, and deletion decision SHALL be authorized and audited.

#### Scenario: Download a released document
- **WHEN** an authorized user downloads an AVAILABLE document
- **THEN** the system issues a short-lived private access response and records actor, document, matter, and correlation ID
