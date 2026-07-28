## Why

The current application is functionally strong enough for a controlled single-firm pilot, but it still contains commercial launch blockers in tenant identity, incomplete user workflows, document security, localization, compliance, production operations, and automated quality assurance. Closing P0 first and then adding P1 legal-practice capabilities will turn the existing prototype into a repeatable private-deployment product for law firms of up to 100 users.

## What Changes

- Bind every authenticated session and DingTalk identity to an explicit organization and reject ambiguous cross-organization identity resolution.
- Complete the visible matter, party, contract, deadline, approval, notification, archive, and document user journeys; remove no-op controls.
- Introduce a quarantined document-ingestion lifecycle with signature inspection, archive/macro policy checks, malware-scanner integration, asynchronous verification, cleanup, and audited release/download.
- Complete customer-facing Chinese/English localization and repair keyboard focus, touch targets, responsive tables, empty states, and status-label presentation.
- Add production security, TLS/reverse-proxy, Redis security, monitoring, backup/restore, release/rollback, privacy/compliance, and incident-response baselines.
- Add consistent server-side pagination, search, sorting, and bounded exports for business lists.
- Add document preview, version history/comparison metadata, OCR/full-text integration points, templates, retention rules, and legal holds.
- Add engagement, time-entry, billing, invoice, collection, and law-firm operational reporting capabilities.
- Add an administration center for users, roles, offices, workflow configuration, organization synchronization, audit review, and security events.
- Add CI quality gates for backend/frontend tests, authorization regressions, dependency/security/license scanning, accessibility, production configuration, and browser E2E.
- **BREAKING**: Authentication/session claims and selected identity APIs will require an organization identifier; legacy username-only sessions will be invalidated.
- **BREAKING**: Document download availability will depend on a successful security-scan state rather than upload completion alone.

## Capabilities

### New Capabilities

- `tenant-identity-security`: Organization-bound local and DingTalk authentication, session claims, tenant configuration, authorization isolation, and tenant-negative tests.
- `core-legal-workflows`: Complete matter, party, conflict, contract, deadline, approval, notification, archive, and document user journeys.
- `secure-document-governance`: Quarantined ingestion, content validation, malware policy, cleanup, preview/version governance, retention, legal hold, and audited access.
- `bilingual-accessible-experience`: Complete Chinese/English content, consistent terminology, keyboard focus, touch targets, responsive layouts, and accessible status feedback.
- `production-compliance-readiness`: Deploy/rollback, TLS, secrets and Redis security, monitoring, backup/restore, privacy rights, incident response, SLA/DPA, and cross-border policy baselines.
- `legal-practice-finance`: Engagement terms, timekeeping, billing, invoices, collections, and operational reporting for a 100-user law firm.
- `administration-and-delivery`: Administration center, organization/office/role management, workflow configuration, customer initialization, bounded import/export, and deployment configuration.
- `engineering-quality-gates`: Isolated unit/integration/E2E tests, authorization and file-security regressions, coverage gates, SAST/DAST/SBOM/license checks, and accessibility validation.

### Modified Capabilities

None. This repository did not previously contain OpenSpec capability specifications.

## Impact

- Backend: Spring Boot security/session handling, DingTalk integration, domain services/controllers, Flyway migrations, scheduled jobs, storage abstraction, Flowable actions, validation, audit, and operational endpoints.
- Frontend: Vue router, API client, i18n catalog, list/detail/form views, notification shell, administration, billing, document, and responsive/accessibility styles.
- Infrastructure: Docker Compose, reverse proxy/TLS, PostgreSQL, Redis, S3-compatible object storage, malware scanner, monitoring, backups, CI, release metadata, and environment configuration.
- External integrations: real DingTalk OAuth/organization sync, optional antivirus engine, OCR, electronic signature, mail/calendar, and invoice services; implementations must remain disabled or simulated until customer-owned credentials are supplied.
- Delivery: privacy notice, data-processing agreement, SLA, incident runbook, support policy, release checklist, migration guide, SBOM, and third-party notices.
