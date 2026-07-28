## Context

The system is a Spring Boot/Vue modular monolith using PostgreSQL, Redis, Flowable, and S3-compatible storage. It already supports a broad legal/OA data model and has a working local E2E path, but commercial review found unsafe username-only session resolution, ambiguous organization selection, incomplete UI actions, synchronous file verification, partial localization, low automated coverage, and an incomplete production/compliance envelope.

The first supported commercial topology is one law firm per private deployment with up to 100 users and multiple offices. P0 establishes a safe, operable pilot. P1 makes that deployment repeatable and adds practice-finance and document-governance capabilities. External provider activation remains configuration-dependent.

## Goals / Non-Goals

**Goals:**

- Deliver a safe single-firm private deployment with explicit organization identity on every authenticated request.
- Make every visible P0 business control complete, authorized, auditable, bilingual, and testable.
- Fail closed for unscanned or policy-violating documents.
- Establish production deployment, rollback, monitoring, backup, privacy, and incident-response evidence.
- Add the P1 capabilities needed for repeatable law-firm delivery: pagination, document governance, time/billing, administration, quality gates, accessibility, and mobile usability.
- Keep the system supportable by a small team through a modular-monolith architecture and bounded external adapters.

**Non-Goals:**

- Shared multi-tenant SaaS operation during P0/P1.
- Full trust accounting, tax filing, payroll, or general ERP.
- Building proprietary malware, OCR, electronic-signature, mail, calendar, or invoice-provider engines.
- AI-generated legal advice or autonomous legal decisions.
- Activating customer-facing integrations without customer-owned credentials and acceptance tests.

## Decisions

### 1. Phase-gated delivery

P0 and P1 are implemented behind explicit acceptance checklists. P1 database migrations may be added after P0, but no P1 capability can weaken P0 security or delay P0 fixes. Each task is complete only after unit/integration tests and, for visible behavior, browser E2E.

Alternative considered: a single large release. Rejected because it makes security regressions and customer acceptance harder to isolate.

### 2. Organization-bound authentication subject

Redis sessions will store a versioned subject containing `userId`, `organizationId`, `username`, and expiry, rather than a username. Production tenant configuration will be resolved from an explicit configured organization/host mapping, never from the first database row. DingTalk state and exchange will bind the intended organization, and the external identity lookup will require the same organization.

The web session will move to a Secure, HttpOnly, SameSite cookie in production. Bearer compatibility may be retained temporarily for migration and automated API tests, but the browser will not persist tokens in Web Storage. Existing sessions are invalidated when the subject schema changes.

Alternative considered: retaining username-only sessions for private deployments. Rejected because duplicate data, imports, or future topology changes could cross an organization boundary.

### 3. Single-firm private deployment as the supported boundary

Each production deployment requires a configured organization identifier and canonical host. Multiple offices remain first-class authorization scopes. Database RLS and shared-SaaS provisioning are deferred until a separate SaaS change, but cross-organization negative tests are mandatory now.

### 4. Quarantined document state machine

Uploads use the lifecycle `PENDING_UPLOAD -> QUARANTINED -> SCANNING -> AVAILABLE | REJECTED | FAILED`. Download and preview require `AVAILABLE`. Validation combines size, allowed extension, normalized filename, detected file signature, archive-entry limits, macro policy, SHA-256, and an antivirus adapter. Production fails closed when the scanner is unavailable; development may use an explicit safe test adapter.

The upload-completion request only verifies object metadata and queues scanning. Large hashing/scanning happens outside the business transaction. A scheduled cleanup removes expired upload records and their unreferenced objects. Security state changes and downloads are audited.

Alternative considered: synchronous scanning in the completion transaction. Rejected because 200 MB files can exhaust request workers and hold database transactions.

### 5. Complete business workflows through shared interaction patterns

Matter detail becomes the central navigation boundary. Create/edit forms use validated drawers or dedicated routes, destructive actions require confirmation, and every list offers loading, empty, success, and error states. Approval actions share one action component for approve, reject, transfer, and remind. Notifications are accessible from the shell and deep-link to the relevant record.

### 6. Catalog-driven bilingual UI and accessibility contract

All customer-facing text and status labels use typed i18n keys. CI scans for unapproved literals and verifies both locale catalogs. The existing refined green/gold editorial law-firm style remains; new components use the same tokens. Keyboard focus is always visible, controls meet touch-target expectations, dialogs manage focus, and wide tables switch to cards or controlled scrolling on small screens.

### 7. Production and compliance as versioned product artifacts

Production Compose includes a reverse-proxy/TLS boundary, health checks, authenticated/TLS-capable Redis configuration, monitoring exporters, and an optional ClamAV service. Secrets are injected, never committed. Release metadata, migration checks, backup, restore verification, rollback instructions, privacy notice, DPA template, SLA, incident runbook, data rights, retention, and cross-border decision records are versioned and included in release acceptance.

### 8. Consistent pagination and bounded exports

Business lists use a common `PageResponse<T>` contract with `items`, `page`, `size`, `total`, and sort metadata. Default and maximum page sizes are enforced server-side. Search and sort fields are allow-listed. Exports are authorized, audited, size-bounded, and asynchronous when necessary.

### 9. P1 practice-finance ledger

Time entries reference a matter, professional, date, duration, activity, rate snapshot, currency, and billable status. Invoices use immutable line snapshots and explicit state transitions. Payments/collections append to an auditable ledger; posted invoices are corrected through adjustment/credit records instead of destructive edits.

Alternative considered: integrating a full accounting package into the core. Rejected; provider adapters and exports preserve a narrow product boundary.

### 10. Evidence-based quality gates

Tests use isolated test data and mocked external providers. Required gates include backend unit/integration tests, authorization-negative tests, frontend component tests, Playwright E2E, production configuration validation, dependency/SBOM/license scan, accessibility scan, and backup/restore verification. Coverage thresholds rise incrementally by changed package and must never reduce the baseline.

## Risks / Trade-offs

- [Large P0/P1 scope] → Deliver in small schema-compatible slices and keep the OpenSpec checklist authoritative.
- [External antivirus/OCR/e-sign providers unavailable] → Use explicit adapters and safe fakes; do not claim production integration until credentialed acceptance passes.
- [Session migration logs users out] → Announce the maintenance window and intentionally invalidate legacy sessions.
- [File scanning increases upload latency] → Expose scan state in the UI, process asynchronously, and alert on queue age.
- [Bilingual terminology can be legally ambiguous] → Maintain a reviewed legal terminology glossary and allow customer terminology overrides later.
- [Finance features can be mistaken for accounting] → Scope UI and contracts to matter billing/collections and provide controlled exports.
- [Parent repository has unrelated dirty changes] → Limit all edits and validation to `law_firm_oa`; do not alter or clean unrelated work.

## Migration Plan

1. Establish the repository-local release baseline and run the existing backend, frontend, API smoke, browser, backup, and production-config checks.
2. Add backward-compatible database migrations for tenant-bound sessions/supporting configuration and document scan states.
3. Deploy P0 to staging with legacy sessions invalidated and document scanning in fail-closed test mode.
4. Complete authorization, file-security, bilingual, and workflow E2E acceptance; capture backup/restore and rollback evidence.
5. Activate real DingTalk and antivirus adapters only after customer resources are supplied and credentialed staging acceptance passes.
6. Release P0 pilot with monitoring and support runbooks.
7. Add P1 capabilities in additive migrations, pilot them with limited roles, then expand after reconciliation and reporting acceptance.

Rollback keeps database migrations forward-compatible, disables new feature flags, restores the previous application image, and preserves newly created records. Destructive down-migrations are not used.

## Open Questions

- Customer organization ID, canonical domain, TLS/DNS control, and production network topology.
- Customer DingTalk app credentials, callback domain, organization/office mapping, and test accounts.
- Antivirus deployment choice and malware-definition update path.
- OCR, electronic-signature, invoice, mail, and calendar providers for P1.
- Customer-approved bilingual terminology, privacy entity, retention periods, data residency, backup region, RPO/RTO, and SLA targets.
- Billing rules, currencies, tax/fapiao workflow, and finance-system export format.
