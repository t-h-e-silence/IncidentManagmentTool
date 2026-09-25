# Architecture Decision Records

**Updated:** 2026-09-25

## About these records
- **Template:** Nygard (Title, Status, Context, Decision, Consequences), extended with *Alternatives considered* and *Confirmation* (the automated check / fitness function that shows the decision is still followed).
- **One decision per ADR.** Titles are present-tense imperative phrases.
- **Status values:** `Proposed | Accepted | Rejected | Deprecated | Superseded by ADR-xxxx`. All records are `Proposed` until the review. After acceptance, records are **not edited**; a change is made by a new ADR that supersedes the old one.
- **Domain background:** [domain-model.md](../domain-model.md).
- **Layout:** one file per ADR, `<topic>/NNNN-imperative-title.md`. Numbers are global and never reused, so code comments can cite `ADR-NNNN`.
- **New ADR:** take the next free number, put the file in the matching topic folder (or add a topic), and add a row below.
- **Related docs:** [system-design.md](../system-design.md), [c4-model.md](../c4-model.md).

## Index by topic

### Architecture — Architecture style, module communication and event contracts

| # | Title | Status | Requirement |
|---|---|---|---|
| 0001 | [Use a modular monolith](architecture/0001-use-a-modular-monolith.md) | Proposed | ADR: modular monolith |
| 0007 | [Query synchronously, publish side effects asynchronously](architecture/0007-query-synchronously-publish-side-effects-asynchronously.md) | Proposed | Sync/async choice |
| 0014 | [Evolve event schemas additively with a schema version](architecture/0014-evolve-event-schemas-additively-with-a-schema-version.md) | Proposed | Event contracts |

### Data — Data storage, ownership and lifecycle

| # | Title | Status | Requirement |
|---|---|---|---|
| 0002 | [Use PostgreSQL as the only data store](data/0002-use-postgresql-as-the-only-data-store.md) | Proposed | Constraint: PostgreSQL or in-memory |
| 0003 | [Give each module its own database schema](data/0003-give-each-module-its-own-database-schema.md) | Proposed | ADR: data ownership |
| 0015 | [Deactivate or archive referenced data instead of deleting it](data/0015-deactivate-or-archive-referenced-data-instead-of-deleting-it.md) | Proposed | Data ownership; references between modules |

### Organization — Users, teams and roles

| # | Title | Status | Requirement |
|---|---|---|---|
| 0004 | [Keep users and teams in one organization module](organization/0004-keep-users-and-teams-in-one-organization-module.md) | Proposed | Module design |
| 0005 | [Assign roles per team, not per user](organization/0005-assign-roles-per-team-not-per-user.md) | Proposed | Q: incident vs team information |

### Security — Authentication and authorization

| # | Title | Status | Requirement |
|---|---|---|---|
| 0013 | [Authenticate users and authorize incident access by team](security/0013-authenticate-users-and-authorize-incident-access-by-team.md) | Proposed | Q: who may do what |

### Incidents — Incident routing, ownership and escalation

| # | Title | Status | Requirement |
|---|---|---|---|
| 0006 | [Route incidents to teams by category](incidents/0006-route-incidents-to-teams-by-category.md) | Proposed | Assignment rules |
| 0011 | [Assign incidents to teams and escalate by reassignment or priority](incidents/0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md) | Proposed | Assignment rules; escalation |

### Messaging — Event delivery, retries and dead letters

| # | Title | Status | Requirement |
|---|---|---|---|
| 0008 | [Publish domain events through a transactional outbox](messaging/0008-publish-domain-events-through-a-transactional-outbox.md) | Proposed | Notification delivery; Q: same transaction |
| 0009 | [Retry failed deliveries and dead-letter them](messaging/0009-retry-failed-deliveries-and-dead-letter-them.md) | Proposed | Q: where RabbitMQ fits |
| 0012 | [Deliver incident events to notifications through RabbitMQ](messaging/0012-deliver-incident-events-to-notifications-through-rabbitmq.md) | Proposed | Q: where RabbitMQ fits |

### Audit — Audit trail

| # | Title | Status | Requirement |
|---|---|---|---|
| 0010 | [Make the audit log append-only](audit/0010-make-the-audit-log-append-only.md) | Proposed | Q: what is immutable in audit |
