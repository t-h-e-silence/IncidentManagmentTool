# ADR-0002: Use PostgreSQL as the only data store

## Status
Proposed — 2026-09-25

## Context
The assignment brief requires PostgreSQL or an in-memory substitute. Two alternatives were raised: a relational DB for users with a NoSQL (document) DB for incidents, or plain in-memory storage. An incident state change must commit atomically with its outbox event ([ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md)). That is only possible when both live in the same transactional database.

## Decision
- Use a single **PostgreSQL** instance for all modules.
- Flexible incident attributes (labels, custom fields) use a **`JSONB`** column instead of a document database.
- Schema migrations use **Flyway**. Integration tests run against **Testcontainers** PostgreSQL.

## Consequences
- **Easier:**
  - incident + outbox commit in one local ACID transaction;
  - only one database to operate;
  - JSONB provides document-style flexibility.
- **Harder:**
  - all modules share one database server, so they share load and outages until extraction;
  - a document store or search engine may later fit incident search and audit timeline queries better.
- **Follow-on ADRs:** schema per module ([ADR-0003](0003-give-each-module-its-own-database-schema.md)).
- **Revisit when:**
  - full-text search over incidents or the audit timeline is needed (e.g. by the AI assistant), or
  - `audit.audit_entry` grows beyond what indexed PostgreSQL queries serve in < 1 s for a timeline.

  The expected answer is an **OpenSearch read copy** fed from audit events, with PostgreSQL staying the source of truth, not a replacement store.

## Alternatives considered
- **PostgreSQL + MongoDB for incidents:** rejected. The incident and the outbox/audit could not share a transaction, it adds infrastructure, and it expands scope beyond the constraint. Revisit if incident query patterns demand it.
- **In-memory storage:** rejected because it cannot demonstrate transactional guarantees or the outbox.
- **Audit in a NoSQL / search store (OpenSearch, Cassandra):** deferred. Audit is append-only and outside the incident transaction, so it is the best NoSQL candidate, but volume is small and the `INSERT`/`SELECT`-only guarantee ([ADR-0010](../audit/0010-make-the-audit-log-append-only.md)) is simplest in PostgreSQL. See *Revisit when*.
- **Redis for notifications (idempotency keys, retry scheduling):** rejected. Creating the `Notification` rows and recording the processed event must be one transaction; splitting them across stores reopens the dual-write problem.

## Confirmation
An integration test uses Testcontainers PostgreSQL. It rolls back an incident creation and asserts that neither the incident row nor the outbox row exists.
