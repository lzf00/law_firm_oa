## 1. Repository and Baseline

- [x] 1.1 Initialize the standalone Git repository, connect the authorized GitHub remote, and publish the protected implementation branch without including secrets or generated artifacts
- [x] 1.2 Capture backend, frontend, API smoke, browser E2E, production-config, security-scan, and backup/restore baseline results
- [x] 1.3 Add release version metadata, changelog convention, migration checklist, and rollback checklist

## 2. P0 Tenant Identity Security

- [x] 2.1 Add a versioned organization-bound session subject and invalidate username-only sessions
- [x] 2.2 Resolve request actors by session user ID and organization ID and reject inactive or mismatched membership
- [x] 2.3 Require explicit production tenant selection and remove first-organization fallback
- [x] 2.4 Bind DingTalk state/exchange and external-user lookup to the selected organization
- [x] 2.5 Move the production browser session to Secure HttpOnly SameSite cookies with logout and expiry handling
- [x] 2.6 Add two-organization authentication, duplicate-username, duplicate-external-ID, and cross-tenant denial tests

## 3. P0 Core Legal Workflow Completion

- [x] 3.1 Implement localized create/edit forms for parties and clients with validation and list refresh
- [x] 3.2 Implement localized create/edit forms for contracts and contract workflow submission
- [x] 3.3 Implement localized create/edit forms for deadlines with ownership, reminders, and matter linkage
- [ ] 3.4 Add the matter detail route and authorized overview for parties, conflicts, team, contracts, deadlines, documents, approvals, activity, and archive state
- [ ] 3.5 Wire matter status filters, search, edit, and lifecycle actions and remove all no-op controls
- [ ] 3.6 Add approval reject-reason, transfer-eligibility, reminder-rate-limit, and action feedback UI
- [ ] 3.7 Add the notification drawer, unread count, mark-read actions, authorized deep links, and inaccessible-target behavior
- [ ] 3.8 Add archive-item management and link it to matter detail and document access rules
- [ ] 3.9 Add unit, API, and browser tests for all P0 workflow success, validation, and authorization paths

## 4. P0 Bilingual and Accessible Experience

- [ ] 4.1 Move all customer-facing literals and raw business statuses into the reviewed Chinese/English catalog
- [ ] 4.2 Add a shared bilingual legal terminology/status formatter and locale completeness tests
- [ ] 4.3 Add visible focus states, dialog focus management, accessible names, validation associations, and live status messages
- [ ] 4.4 Fix mobile page overflow, touch targets, navigation, dialogs, and responsive business-list presentation
- [ ] 4.5 Add both-locale desktop/mobile browser traversal with console, network, literal, and accessibility assertions

## 5. P0 Secure Document Ingestion

- [ ] 5.1 Add Flyway migration and domain states for quarantined, scanning, available, rejected, and failed documents
- [ ] 5.2 Normalize filenames and implement server-side signature, extension, size, archive-limit, and macro-policy inspection
- [ ] 5.3 Add an antivirus adapter with a deterministic test implementation and fail-closed production configuration
- [ ] 5.4 Move hashing and scanning outside the upload-completion database transaction and expose scan status
- [ ] 5.5 Restrict preview/download to available documents and audit all scan, override, preview, download, export, and deletion decisions
- [ ] 5.6 Add scheduled cleanup for expired incomplete uploads and unreferenced quarantine objects with metrics
- [ ] 5.7 Add malicious-signature, macro/archive, scanner-failure, oversized-file, cross-office, and cleanup regression tests

## 6. P0 Production, Compliance, and Release Readiness

- [ ] 6.1 Add reverse-proxy/TLS, secure-header, API-rate-limit, health-check, and environment validation configuration
- [ ] 6.2 Add authenticated/TLS-capable Redis settings, least-privilege storage configuration, and non-secret integration health
- [ ] 6.3 Add service, database-pool, job, scan-queue, notification, security-event, and backup metrics with alert guidance
- [ ] 6.4 Harden backup encryption/retention and automate isolated restore verification and evidence output
- [ ] 6.5 Add privacy notice, processing inventory, data-rights workflow, complaint channel, retention, incident-response, and cross-border decision templates
- [ ] 6.6 Add SLA, DPA, support policy, release notes, migration guide, SBOM, license notices, and customer acceptance checklist
- [ ] 6.7 Add real DingTalk, domain/TLS, antivirus, residency, RPO/RTO, and customer terminology acceptance checklist for supplied resources

## 7. P0 Acceptance Gate

- [ ] 7.1 Run all backend unit/integration tests and generate package-level coverage evidence
- [ ] 7.2 Run frontend unit tests and both-locale desktop/mobile Playwright acceptance with zero critical accessibility violations
- [ ] 7.3 Run API smoke, tenant-isolation, file-security, dependency/image, production-config, backup/restore, and rollback checks
- [ ] 7.4 Publish the P0 release candidate and document remaining customer-resource-dependent acceptance items

## 8. P1 Pagination and Document Governance

- [ ] 8.1 Introduce the common bounded `PageResponse` contract and apply allow-listed search/sort/pagination to all business lists
- [ ] 8.2 Add audited bounded exports and asynchronous export handling for large result sets
- [ ] 8.3 Add document preview, version history, comparison metadata, and safe rendition lifecycle
- [ ] 8.4 Add OCR/full-text provider adapter, indexing state, authorized search, and safe test implementation
- [ ] 8.5 Add document templates and clause-library management with versioning and office scope
- [ ] 8.6 Add retention rules, legal-hold create/release workflow, blocked deletion, and audit UI
- [ ] 8.7 Add unit, API, authorization, performance-boundary, and browser tests for pagination and document governance

## 9. P1 Legal Practice Finance

- [ ] 9.1 Add engagement and fee-term schema, APIs, approval state, forms, and matter-detail integration
- [ ] 9.2 Add time-entry schema, rate snapshots, validation, approval, entry UI, and matter/professional views
- [ ] 9.3 Add invoice draft/review/issue/cancel/adjust lifecycle with immutable issued line snapshots
- [ ] 9.4 Add payment allocation, collection activity, outstanding balance, due date, and reconciliation ledger
- [ ] 9.5 Add office-scoped WIP, unbilled time, revenue, receivables aging, collection, and utilization reports
- [ ] 9.6 Add finance export/provider adapter boundaries for fapiao/accounting integration without embedding provider credentials
- [ ] 9.7 Add finance calculation, lifecycle, authorization, reconciliation, report, and browser regression tests

## 10. P1 Administration and Repeatable Delivery

- [ ] 10.1 Add permission-protected administration navigation and organization branding/settings pages
- [ ] 10.2 Add user, role, permission, office-membership, temporary-access, and last-administrator safeguards
- [ ] 10.3 Add DingTalk directory-sync dry run, diff, office/role mapping, idempotent apply, and audit summary
- [ ] 10.4 Add workflow assignment, escalation, reminder, and fallback configuration with eligibility validation
- [ ] 10.5 Add integration-health pages for DingTalk, storage, scanner, OCR, e-sign, mail, calendar, and invoice adapters
- [ ] 10.6 Add validated organization-bound import templates and bounded exports with row-level error reports
- [ ] 10.7 Add customer initialization, upgrade compatibility, migration rehearsal, and rollback acceptance tests

## 11. P1 Engineering Quality and UX Hardening

- [ ] 11.1 Add CI for backend/frontend builds, isolated tests, changed-code coverage, and production configuration
- [ ] 11.2 Add secret detection, dependency and image vulnerability scans, SAST, SBOM generation, and license policy
- [ ] 11.3 Add automated accessibility, localization completeness, unapproved-literal, responsive-route, and dead-control checks
- [ ] 11.4 Add authorization matrix tests for every controller and cross-organization/office negative cases
- [ ] 11.5 Add file-upload concurrency, list pagination, report, and 100-user representative load tests
- [ ] 11.6 Improve changed-package test coverage without reducing the repository baseline and publish coverage trends

## 12. P1 Commercial Release Acceptance

- [ ] 12.1 Run the complete backend, frontend, API, browser, security, accessibility, load, production, backup/restore, and rollback suite
- [ ] 12.2 Conduct a two-locale role-based user acceptance rehearsal for lawyer, partner, finance, HR/office, records, and administrator personas
- [ ] 12.3 Produce the commercial release report, known limitations, operations handover, support runbook, and customer resource checklist
- [ ] 12.4 Publish the P1 release candidate to the authorized GitHub repository with immutable version and evidence links
