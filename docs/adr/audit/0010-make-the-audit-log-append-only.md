# ADR-0010: Make the audit log append-only

## Status
Proposed — 2026-09-25

## Context
The audit trail must be a trustworthy record of significant actions: create, status, severity and priority changes, comments, escalations and configuration changes. If entries could be edited or deleted, the record would lose its value for investigations. It is also the source for the incident timeline and for the future AI investigation assistant.

"Trustworthy" has two parts:
- entries can't be **changed**;
- entries can't be **missing**.

Code discipline alone covers neither: a developer can add an `UPDATE` by mistake, and an audit consumer that fails leaves a silent gap.

## Decision
- The `audit` module stores one `AuditEntry` per consumed domain event, idempotent on `UNIQUE(event_id)`:
  - `id`, `eventId`, `actorId`, `action`, `entityType`, `entityId`, `occurredAt`, `payload`, `correlationId`;
  - `correctsEntryId`, set only on correction entries;
  - `incidentId` (proposed), set for everything about an incident so that the timeline includes escalations and notifications.
- **All fields are immutable, and the table is append-only.** This is enforced on three levels:
  - **database:** the application role `ims_app` has only `INSERT` and `SELECT` on `audit.audit_entry`. Only `ims_migrator` owns the table ([ADR-0003](../data/0003-give-each-module-its-own-database-schema.md)).
  - **code:** `AuditEntry` is an immutable record, and the audit repository exposes no update or delete methods.
  - **API:** `AuditApi` is read-only.
- **Corrections** are written as a **new** entry (`action = CORRECTION`) that references the original through `correctsEntryId`. The original stays unchanged, and the timeline shows both.
- **No gaps.** Audit entries are written **after** commit, through the outbox ([ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md)), as their own `audit` subscriber. A dead-lettered `audit` delivery raises an alert ([ADR-0009](../messaging/0009-retry-failed-deliveries-and-dead-letter-them.md)) and is replayed. Because the insert is idempotent, a replay can't create duplicates.
- **Payloads hold ids, not personal data** such as emails or phone numbers, so the log can stay immutable even when a user's contact data changes.

## Consequences
- **Easier:**
  - tampering by the application is impossible, not just discouraged;
  - a missing entry is visible (dead-letter alert) and recoverable (replay);
  - it is a natural source for the incident timeline and future MCP "timeline" resources.
- **Harder:**
  - audit is eventually consistent (seconds behind);
  - storage only grows, so a retention or archiving policy will be needed later;
  - mistakes can't be fixed in place; readers must take corrections into account;
  - a database superuser can still change rows. Protecting against that needs the hash chain below.
- **Follow-on ADRs:** audit retention and archiving; hash-chaining, if tamper evidence against DB administrators becomes a requirement.

## Alternatives considered
- **Write audit in the same transaction as the incident:** gives immediacy, but couples `audit` to every module's transaction and prevents extracting it. Rejected; the outbox already guarantees the entry will be written.
- **Mutable audit table:** rejected because the record can't be trusted.
- **Hash-chained entries** (each entry stores a hash of the previous one): deferred. It detects tampering even by a DB superuser, but it serializes inserts and needs a verification job. Not required for v1.
- **Separate append-only store (OpenSearch, S3 with Object Lock):** deferred; see [ADR-0002](../data/0002-use-postgresql-as-the-only-data-store.md).

## Confirmation
- A test asserts that the audit repository has no update or delete methods.
- A Flyway migration grants `ims_app` only `INSERT`/`SELECT`. An integration test connected as `ims_app` asserts that `UPDATE` and `DELETE` on `audit.audit_entry` fail.
- An integration test replays the same event twice and asserts one entry.
