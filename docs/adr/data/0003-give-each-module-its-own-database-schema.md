# ADR-0003: Give each module its own database schema

## Status
Proposed — 2026-09-25

## Context
The assignment brief forbids casual cross-module repository or entity access. If modules share tables, none of them can change its data model or be extracted without breaking the others (the shared-database anti-pattern).

## Decision
- Use one PostgreSQL schema per module: `organization`, `incidents`, `escalations`, `notifications`, `audit`.
- A module reads and writes **only its own schema**. It gets other modules' data through their `api` or their events.
- **No foreign keys across schemas.** References are plain IDs (e.g. `incidents.incident.team_id`). They stay valid because referenced rows are never deleted ([ADR-0015](0015-deactivate-or-archive-referenced-data-instead-of-deleting-it.md)).
- **Database roles:**
  - `ims_migrator` owns all schemas and runs Flyway (`spring.flyway.user`);
  - `ims_app` is the role the application connects with. It has DML rights on its tables but no DDL, and on `audit.audit_entry` only `INSERT` and `SELECT` ([ADR-0010](../audit/0010-make-the-audit-log-append-only.md));
  - when a module is extracted, it gets its own app role limited to its own schema.
- Each module has its own Flyway migration folder (`db/migration/<module>`).

## Consequences
- **Easier:**
  - ownership is visible;
  - extracting a module means moving its schema to its own database.
- **Harder:**
  - no DB-level referential integrity between modules;
  - no cross-module joins, so combined views must be built through APIs or read models.

## Alternatives considered
- **Shared tables in one schema:** rejected because ownership is invisible and extraction is hard.
- **A separate database per module now:** rejected because it gives no benefit before extraction and adds operational cost.
- **One DB role per module inside the monolith:** rejected for now. It needs one datasource and transaction manager per module and gives little over the ArchUnit rule while everything runs in one process.

## Confirmation
- An ArchUnit rule: JPA repositories and entities of module X are accessed only from classes in module X. **Not in place yet:** `archunit-junit5` must be added to `pom.xml` (test scope).
- An integration test connects as `ims_app` and asserts that `CREATE TABLE` fails in every schema.
