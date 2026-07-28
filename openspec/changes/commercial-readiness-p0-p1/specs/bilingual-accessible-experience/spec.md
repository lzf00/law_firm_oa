## ADDED Requirements

### Requirement: Complete localized content
All customer-facing navigation, forms, validation, statuses, dialogs, notifications, empty states, and error messages SHALL have reviewed `zh-CN` and `en-US` translations.

#### Scenario: Traverse in English
- **WHEN** a user selects English and visits every supported route
- **THEN** no unintended Chinese interface literal or raw backend status code is displayed

### Requirement: Stable legal terminology
The product SHALL use one reviewed bilingual glossary for matter, party, conflict, engagement, approval, document, archive, time, invoice, and collection concepts.

#### Scenario: Same status across modules
- **WHEN** a shared status appears in a list, detail, notification, or export
- **THEN** the same locale-specific term is used in each location

### Requirement: Keyboard and focus accessibility
All interactive controls SHALL be keyboard reachable, SHALL expose a visible focus indicator, and dialogs SHALL manage focus and accessible names.

#### Scenario: Complete a form by keyboard
- **WHEN** a keyboard-only user opens, completes, validates, and closes a create form
- **THEN** focus order, validation association, and focus restoration remain correct

### Requirement: Responsive touch experience
Supported mobile layouts SHALL avoid unintended page-level horizontal overflow and SHALL provide touch targets and responsive data presentation suitable for common phone widths.

#### Scenario: Use a business list at 390 pixels
- **WHEN** a user opens a supported list at a 390-pixel viewport
- **THEN** primary actions remain reachable and records are readable without page-level horizontal scrolling

### Requirement: Automated accessibility and localization checks
CI SHALL scan for missing locale keys, unapproved literals, critical accessibility violations, and responsive route failures.

#### Scenario: Missing English key
- **WHEN** a customer-facing key exists only in the Chinese catalog
- **THEN** the frontend quality gate fails
