# Incident Management System — Architecture Decision Records

**Updated:** 2026-09-25

## About this log
- **Template:** Nygard (Title, Status, Context, Decision, Consequences), extended with *Alternatives considered* and *Confirmation* (the automated check / fitness function that shows the decision is still followed).
- **One decision per ADR.** Titles are present-tense imperative phrases.
- **Status values:** `Proposed | Accepted | Rejected | Deprecated | Superseded by ADR-xxxx`. All records are `Proposed` until the HW1 review. After acceptance, records are **not edited**; a change is made by a new ADR that supersedes the old one.
- **Domain background:** [domain-model.md](domain-model.md).

## Index
| # | Title | Status | HW1 requirement                            |
|---|---|---|--------------------------------------------|
| 0001 | Use a modular monolith | Proposed | ADR: modular monolith                      |
| 0002 | Use PostgreSQL as the only data store | Proposed | Constraint: PostgreSQL or in-memory        |
| 0003 | Give each module its own database schema | Proposed | ADR: data ownership                        |
| 0004 | Keep users and teams in one organization module | Proposed | Module design                              |
| 0005 | Assign roles per team, not per user | Proposed | Q: incident vs team information            |
| 0006 | Route incidents to teams by category | Proposed | Assignment rules                           |
| 0007 | Query synchronously, publish side effects asynchronously | Proposed | Sync/async choice                          |
| 0008 | Deliver notifications outside the incident transaction | Proposed | Notification delivery; Q: same transaction |
| 0009 | Retry failed deliveries and dead-letter them | Proposed | Q: where RabbitMQ fits                     |
| 0010 | Make the audit log append-only | Proposed | Q: what is immutable in audit              |
| 0011 | Assign incidents to teams and escalate by reassignment or priority | Proposed | Assignment rules; escalation |

---

## ADR-0001: Use a modular monolith

### Status
Proposed — 2026-09-25

### Context
The system covers several sub-domains: organization (users and teams), incidents, escalations, notifications and audit. HW1 requires strong internal boundaries but forbids microservices at this stage. The team is a single developer working on a one-week timeline, the domain is still being discovered, and boundaries are likely to move. Every module should nevertheless be extractable into its own service later.

### Decision
- Build one deployable **Spring Boot 3 / Java 21** application, with one top-level package per module: `organization`, `incidents`, `escalations`, `notifications`, `audit`.
- Each module exposes only an `api` package (interfaces, DTOs, events). Its domain, persistence and web code are internal.
- Modules interact only through another module's `api` (synchronous calls) or through domain events (ADR-0007, ADR-0008).

### Consequences
- **Easier:**
  - one build and one deployment;
  - simple local development and debugging;
  - in-process calls with no network failures;
  - a clear extraction path: a module's `api` becomes a REST or messaging contract.
- **Harder:**
  - a bug or memory leak in one module affects the whole process;
  - modules can't be scaled or deployed independently;
  - boundaries depend on discipline plus automated checks.
- **Follow-on ADRs:** data ownership (ADR-0003), sync vs async communication (ADR-0007).

### Alternatives considered
- **Microservices now:** rejected. The operational cost (deployment, network failures, distributed tracing) is too high while boundaries are still being discovered, and HW1 forbids it.
- **Layered monolith (controller/service/repository):** rejected. It has no domain boundaries and tends to become tangled over time.

### Confirmation
A Spring Modulith test, `ApplicationModules.of(App.class).verify()`, fails the build on dependency cycles or on access to another module's internal packages.

---

## ADR-0002: Use PostgreSQL as the only data store

### Status
Proposed — 2026-09-25

### Context
HW1 requires PostgreSQL or an in-memory substitute for the thin slice. Two alternatives were raised: a relational DB for users with a NoSQL (document) DB for incidents, or plain in-memory storage. An incident state change must commit atomically with its outbox event (ADR-0008). That is only possible when both live in the same transactional database.

### Decision
- Use a single **PostgreSQL** instance for all modules.
- Flexible incident attributes (labels, custom fields) use a **`JSONB`** column instead of a document database.
- Schema migrations use **Flyway**. Integration tests run against **Testcontainers** PostgreSQL.

### Consequences
- **Easier:**
  - incident + outbox (+ status history) commit in one local ACID transaction;
  - only one database to operate;
  - JSONB provides document-style flexibility.
- **Harder:**
  - all modules share one database server, so they share load and outages until extraction;
  - a document store may later fit incident search patterns better.
- **Follow-on ADRs:** schema per module (ADR-0003).

### Alternatives considered
- **PostgreSQL + MongoDB for incidents:** rejected for HW1. The incident and the outbox/audit could not share a transaction, it adds infrastructure, and it expands scope beyond the HW constraint. Revisit if incident query patterns demand it.
- **In-memory storage:** rejected because it cannot demonstrate transactional guarantees or the outbox.

### Confirmation
An integration test uses Testcontainers PostgreSQL. It rolls back an incident creation and asserts that neither the incident row nor the outbox row exists.

---

## ADR-0003: Give each module its own database schema

### Status
Proposed — 2026-09-25

### Context
HW1 forbids casual cross-module repository or entity access. If modules share tables, none of them can change its data model or be extracted without breaking the others (the shared-database anti-pattern).

### Decision
- Use one PostgreSQL schema per module: `organization`, `incidents`, `escalations`, `notifications`, `audit`.
- A module reads and writes **only its own schema**. It gets other modules' data through their `api` or their events.
- **No foreign keys across schemas.** References are plain IDs (e.g. `incidents.incident.team_id`).
- Each module has its own Flyway migration folder (`db/migration/<module>`).

### Consequences
- **Easier:**
  - ownership is visible;
  - extracting a module means moving its schema to its own database.
- **Harder:**
  - no DB-level referential integrity between modules;
  - no cross-module joins, so combined views must be built through APIs or read models.

### Alternatives considered
- **Shared tables in one schema:** rejected because ownership is invisible and extraction is hard.
- **A separate database per module now:** rejected because it gives no benefit before extraction and adds operational cost.

### Confirmation
An ArchUnit rule: JPA repositories and entities of module X are accessed only from classes in module X. Optionally, one DB role per module with privileges on its own schema only.

---

## ADR-0004: Keep users and teams in one organization module

### Status
Proposed — 2026-09-25

### Context
The brief suggests a `teams` module. We also need users. A separate `identity` module was considered, because every module may become a microservice. After per-team roles (ADR-0005), user data is very small (`id`, `name`, `email`, `systemRole`), and it is almost always used together with team data:
- notifications need "members of team X with their contacts";
- authorization needs both the system role and the team role.

### Decision
- One **`organization`** module (schema `organization`) owns `User`, `Team`, `Membership` and `Category`.
- Public API: `teamForCategory(categoryId)`, `roleOf(teamId, userId)`, `membersOf(teamId)` (members with contacts), `userById(userId)`.
- Users live in an internal sub-package, `organization.user`, with no dependency on team classes, so they can be lifted out later.
- In HW1, users are seeded. The current user arrives in an `X-User-Id` header as a temporary thin-slice shortcut, not a long-term decision; real authentication (OIDC/JWT) is future work.

### Consequences
- **Easier:**
  - fewer modules and synchronous dependencies;
  - one call gives members with contacts;
  - it stays close to the brief's module list and keeps HW1 in scope.
- **Harder:**
  - the module has two reasons to change (identity data vs routing rules);
  - personal data sits next to routing configuration;
  - adopting an external IdP requires extracting `organization.user`;
  - the `X-User-Id` header is insecure and is accepted only as a risk for the thin slice.
- **Revisit when:** an external identity provider (Keycloak, Auth0) is adopted, or user/profile features grow independently of team routing.

### Alternatives considered
- **Separate `identity` and `teams` modules:** clearer separation and easier IdP replacement. Rejected for HW1 because `identity` would be almost empty and every main use case would need both modules.
- **Two modules sharing the same user tables:** rejected (see ADR-0003).
- **External IdP now:** out of scope for HW1.

### Confirmation
An ArchUnit rule: `organization.user..` must not depend on `organization.team..`.

---

## ADR-0005: Assign roles per team, not per user

### Status
Proposed — 2026-09-25

### Context
A single global `UserType` (REPORTER / RESPONDER / ADMIN) was the first idea. It fails two real cases:
- responders often **report** incidents themselves;
- one person may be a **team lead** in one team and a regular **responder** in another.

### Decision
- `User.systemRole` = `USER | ADMIN` (global). Every `USER` can report, so "reporter" is an *action*, not a stored type.
- `Membership(teamId, userId, teamRole)` with `teamRole` = `RESPONDER | TEAM_LEAD` (per team).
- Authorization rules:
  - any user may create an incident;
  - only a member of the incident's team (`roleOf(teamId, userId)` is present) may change its status or severity or comment on it;
  - `ADMIN` manages teams, categories and escalation policies (seeded in HW1).

### Consequences
- **Easier:**
  - anyone can report;
  - the same person can have different roles in different teams;
  - `TEAM_LEAD`-only rules can be added without a model change.
- **Harder:** authorization combines two role sources (system role and team role).
- **Follow-on ADRs:** which actions are restricted to `TEAM_LEAD` (open question).

### Alternatives considered
- **Global `UserType` per user:** rejected for the reasons in Context.

### Confirmation
Unit tests for the authorization policy:
- a non-member gets 403 when changing an incident;
- a member of another team gets 403;
- a member of the assigned team succeeds.

---

## ADR-0006: Route incidents to teams by category

### Status
Proposed — 2026-09-25. Amended by ADR-0011: the owning team can change through escalation.

### Context
Reporters don't know how the organization is structured. Incidents must reach the responsible team immediately, and a reorganization shouldn't require retraining reporters.

### Decision
- The reporter selects a **Category** (e.g. "Payments", "Database", "VPN").
- `organization` maps each category to exactly one team (`Category.teamId`).
- At creation, `incidents` calls `teamForCategory(categoryId)` and stores `teamId`, `categoryId` and a category-name snapshot. It never copies member lists.
- Incidents are assigned to a **team only** (no individual assignee or on-call rotation in v1).

### Consequences
- **Easier:**
  - reporters use familiar terms;
  - a reorganization only means remapping categories;
  - past incidents keep their original team.
- **Harder:**
  - a wrong mapping sends incidents to the wrong team;
  - there is no per-person ownership or on-call yet.
- **Follow-on ADRs:** individual assignment / on-call rotation, if needed later.

### Alternatives considered
- **Reporter picks the team directly:** rejected because it requires knowing the organization's structure.
- **Assign to an individual responder:** rejected for v1 because of scope; it would need on-call rules.

### Confirmation
An integration test: creating an incident with category C stores the team currently mapped to C.

---

## ADR-0007: Query synchronously, publish side effects asynchronously

### Status
Proposed — 2026-09-25

### Context
Modules need to cooperate. Some interactions produce an answer the request can't finish without (which team handles this category? is this user a member?). Others are reactions to something that already happened (notify, audit, escalate). We need one consistent rule.

### Decision
- **Synchronous** in-process calls to another module's `api` when the caller *needs the answer to finish the request*:
  - `incidents → organization.teamForCategory`
  - `incidents → organization.roleOf`
  - `notifications → organization.membersOf`
  - `escalations → organization` (policy target team)
- **Asynchronous** domain events for *side effects*:
  - `IncidentCreated`, `IncidentStatusChanged`, `IncidentSeverityChanged` and `CommentAdded` go to notifications, audit and escalations;
  - `IncidentEscalated` goes to notifications and audit.
- **Event schemas are owned by the publisher** (`incidents.api.events`, `escalations.api.events`). Every event carries `eventId`, `occurredAt`, `actorId`, `correlationId` and `schemaVersion`.
- **Delivery is at-least-once.** Every consumer is **idempotent**, recording processed `eventId`s in its own `processed_events` table.

### Consequences
- **Easier:**
  - requests are fast and never fail because of side effects;
  - publishers don't know their consumers, so new consumers (e.g. the future AI assistant) need no publisher change;
  - it maps directly to RabbitMQ later.
- **Harder:**
  - eventual consistency: notifications and audit entries appear shortly after the commit;
  - duplicate handling adds code;
  - after extraction, an `organization` outage blocks incident creation (mitigation: a cached routing table).
- **Follow-on ADRs:** event delivery mechanism (ADR-0008), failure handling (ADR-0009).

### Alternatives considered
- **Everything synchronous:** rejected. A notification outage would block incident creation.
- **Everything asynchronous, including routing:** rejected. The reporter needs an immediate, consistent answer about which team owns the incident.

### Confirmation
- A Spring Modulith `verify()` test enforces the allowed dependencies.
- A consumer test delivers the same event twice and asserts a single effect.

---

## ADR-0008: Deliver notifications outside the incident transaction

### Status
Proposed — 2026-09-25

### Context
When an incident is created, responders of the assigned team must be notified. Notification channels (email, Slack, SMS) are external, slow and sometimes down. If delivery happened inside the incident transaction:
- a channel outage would block or roll back incident creation, exactly when things are broken;
- a rollback *after* sending would notify people about an incident that doesn't exist.

At the same time, a created incident must **never be silently left unnotified**. Sending only after commit, with no durable record, loses the event if the application crashes in between.

### Decision
- **Transactional outbox.** In one transaction, `incidents` saves the `Incident` and an `IncidentCreated` row in `incidents.outbox`. The row holds `event_id`, `type`, `payload`, `status`, `attempts`, `next_attempt_at` and `correlation_id`.
  - This is the only work in the incident transaction. Notifications, audit and escalation evaluation all happen after commit.
- **Outbox relay.** A scheduled poller reads `PENDING` rows (`FOR UPDATE SKIP LOCKED`) and dispatches them in-process to subscribers. On success, the row is marked `SENT`.
- **Notification intent.** `notifications` consumes the event idempotently and creates one `Notification` per team member. It delivers through a **stub channel** that logs the message and can be configured to fail in tests.

**Failure behavior in HW1:**

| Failure | Result |
|---|---|
| Channel down during delivery | Incident already committed; delivery is retried (ADR-0009) |
| Crash after commit, before dispatch | Outbox row still `PENDING`; dispatched after restart |
| Crash after dispatch, before marking `SENT` | Event redelivered; consumer ignores the duplicate by `eventId` |
| Incident transaction fails | Neither incident nor event exists; nothing is sent |

**Future path to RabbitMQ (not implemented).** The relay publishes to topic exchange `incidents.events` instead of calling subscribers in-process, with publisher confirms. Each consumer module gets its own durable queue with manual acks. The domain code, the outbox and the idempotent consumers stay unchanged; only the relay and listener adapters change.

### Consequences
- **Easier:**
  - incident creation never depends on notification channels;
  - no lost events: each one is persisted before it's dispatched;
  - clean migration to RabbitMQ, which still needs the outbox to avoid dual writes.
- **Harder:**
  - notifications are delayed by the polling interval (seconds);
  - more moving parts (outbox table, poller, idempotency tables);
  - a recipient may rarely get a duplicate message from the external channel (accepted).
- **Follow-on ADRs:** retry and dead-letter policy (ADR-0009), and later "adopt RabbitMQ for event delivery".

### Alternatives considered
- **Send inside the transaction:** rejected (see Context).
- **`@TransactionalEventListener(AFTER_COMMIT)` without an outbox:** simpler, but the event is lost if the application crashes after commit.
- **Introduce RabbitMQ now:** rejected as out of HW1 scope.

### Confirmation
Integration tests (Testcontainers):
- creating an incident while the stub channel fails still returns 201 and leaves an outbox row;
- after the relay runs, a `Notification` exists for each team member.

---

## ADR-0009: Retry failed deliveries and dead-letter them

### Status
Proposed — 2026-09-25

### Context
With the outbox (ADR-0008), events are never lost, but dispatch or notification delivery can still fail repeatedly (a bad payload, a channel that stays down). Retrying forever hides problems and wastes resources. Dropping events after a failure breaks the "never silently unnotified" guarantee.

### Decision
- **Retry with exponential backoff.** This applies to both outbox dispatch and each notification delivery: 5 attempts at about 10 s, 30 s, 1 min, 5 min and 15 min. The status is `RETRYING` in between.
- **Dead letter.** After the last attempt, the status becomes `DEAD_LETTERED`. The record is kept, and:
  - an ERROR log is written with `eventId` or `notificationId` and `correlationId`;
  - the counters `outbox_dead_lettered_total` and `notifications_dead_lettered_total` are incremented;
  - the record can be **replayed manually** by resetting it to `PENDING`.
- **Future RabbitMQ mapping:**
  - a failed message is rejected to a **retry queue with TTL**, which dead-letters it back to the main queue;
  - after N attempts (read from the `x-death` header) it's routed via the **dead-letter exchange** to `<queue>.dlq`.

### Consequences
- **Easier:**
  - transient failures recover automatically;
  - permanent failures stay visible, measurable and replayable;
  - it has the same semantics as RabbitMQ's DLX, so migration is straightforward.
- **Harder:**
  - dead-lettered items need operational attention (alerts on the metric, a replay procedure);
  - retry state adds columns and scheduler logic.

### Alternatives considered
- **Retry forever:** rejected because it hides poison messages and blocks the queue.
- **Log and drop after the first failure:** rejected because notifications would be lost silently.

### Confirmation
An integration test with the stub channel set to always fail: after max attempts, the notification is `DEAD_LETTERED` and the metric has increased by 1. A replay then delivers it once the stub recovers.

---

## ADR-0010: Make the audit log append-only

### Status
Proposed — 2026-09-25

### Context
The audit trail must be a trustworthy record of significant actions (create, status change, severity change, comment, escalation). If entries could be edited or deleted, the record would lose its value for investigations. It's also the planned "timeline" source for the future AI investigation assistant.

### Decision
- The `audit` module stores `AuditEntry(eventId, actorId, action, entityType, entityId, occurredAt, payload, correlationId)`, built from consumed domain events. It is idempotent on `eventId`.
- **All fields are immutable.** The table is **append-only**:
  - the application DB role has only `INSERT` and `SELECT` on `audit.audit_entry`;
  - the audit repository exposes no update or delete methods.
- A correction is written as a **new** entry that references the original.
- Audit entries are written **after** commit via the outbox (ADR-0008), not in the incident transaction.

### Consequences
- **Easier:**
  - a tamper-resistant history;
  - a natural source for the incident timeline and future MCP "timeline" resources.
- **Harder:**
  - audit is eventually consistent (seconds behind);
  - storage only grows, so a retention or archiving policy will be needed later;
  - mistakes can't be fixed in place.
- **Follow-on ADRs:** audit retention and archiving.

### Alternatives considered
- **Write audit in the same transaction as the incident:** gives stronger immediacy, but couples `audit` to every module's transaction and prevents extracting it. Rejected; the outbox already guarantees the entry will be written.
- **Mutable audit table:** rejected because the record can't be trusted.

### Confirmation
- A test asserts that the audit repository has no update or delete methods.
- A Flyway migration grants only `INSERT`/`SELECT`, and an integration test asserts that an `UPDATE` fails.

---

## ADR-0011: Assign incidents to teams and escalate by reassignment or priority

### Status
Proposed — 2026-09-25. Amends ADR-0006: the owning team can now change, through escalation.

### Context
Three questions were still open:
- **Who owns an incident?** The system design proposed an individual `assignee_id`. ADR-0006 rejected per-person ownership for v1 because it needs on-call rules. The team decides internally who works on what.
- **Should an incident keep a priority?** Severity describes *impact* and is set by the reporter. The team needs its own lever for *urgency* and queue order, e.g. a SEV3 that blocks a release.
- **What does escalation do?** The system design (§8) treated escalation as a marker plus a notification. The original team stayed the owner, and the target team also got write access. That leaves two teams able to change one incident, and neither clearly responsible (domain-model open question 2).

In practice, a team escalates in two ways: it hands the incident to a team that can fix it, or it makes the incident more urgent. Both are changes to incident data, so both must follow the single-writer rule from ADR-0003.

### Decision
- **Team ownership only.** An incident belongs to exactly one team (`team_id`). There is no individual assignee. The first team comes from the category (ADR-0006).
- **Priority is kept.** `priority` is `P1..P4`. It defaults from severity (SEV1 → P1 … SEV4 → P4) and drives the team's queue order. Changing severity does not change priority automatically.
- **Escalation is one of two actions on an open incident, or both at once:**
  1. **Reassign** it to another team. `team_id` changes, the new team becomes the only owner, and the previous team keeps read access only.
  2. **Raise priority**, e.g. P3 → P2. Lowering priority is an ordinary change (`IncidentPriorityChanged`), not an escalation.
- **Triggers:**
  - *Manual:* any member of the owning team, with a required `reason`.
  - *Automatic:* the team's escalation policy fires when severity reaches its threshold. It reassigns the incident to the policy's target team and raises priority to at least the severity's default. This happens once per `(incident, policy)`.
- **`incidents` is the only writer of `team_id` and `priority`.** `IncidentApi.escalate(incidentId, targetTeamId?, priority?, reason, actor)` runs in one transaction. It:
  - checks that the incident is not `RESOLVED`;
  - checks that the actor is a member of the owning team, or is the SYSTEM actor;
  - checks the target team through `organization`: it must differ from the current team, exist and not be archived;
  - checks that a requested priority is actually higher;
  - updates the incident, including `escalation_level + 1` and `escalated_at`;
  - writes `IncidentEscalated` to the incidents outbox.
- **`escalations` keeps policies and automatic decisions only.** It consumes severity events and records an `EscalationDecision` (`UNIQUE(incident_id, policy_id)`). An outbox step then calls `IncidentApi.escalate` as SYSTEM, and the call is idempotent by `decisionId`.
- **`IncidentEscalated` moves to `incidents.api.events`**, because the module that makes the change owns its event (ADR-0007). Payload: from/to team, from/to priority, trigger (`MANUAL`, `SEVERITY_THRESHOLD`), reason, and `decisionId` for automatic escalations.
- **Reactions:**
  - `notifications` notifies the members of the new owning team, or of the owning team when only priority was raised (reason `INCIDENT_ESCALATED`);
  - `audit` records the escalation;
  - `escalations` updates `open_incident`.
- **Status is unaffected.** OPEN stays OPEN, and IN_PROGRESS stays IN_PROGRESS.

### Consequences
- **Easier:**
  - there is exactly one owner at any time, so authorization stays `roleOf(team_id, actor)`;
  - manual and automatic escalation follow one code path and produce one event;
  - an incident and its escalation commit together, with no cross-module transaction.
- **Harder:**
  - `team_id` is no longer fixed, so "which teams worked on this" and per-team metrics (MTTA/MTTR per team) must come from the audit timeline;
  - there is no individual accountability; the team handles that outside the system;
  - teams can pass an incident back and forth. `escalation_level` makes that visible, and a limit can be added later;
  - code and design changes:
    - move `IncidentEscalated` from `escalations` to `incidents`;
    - add `priority`, `escalation_level` and `escalated_at` to `Incident`;
    - drop the proposed `assignee_id`, `IncidentAssigned` and `recordEscalation`;
    - update system-design §5.2, §7 and §8.
- **Follow-on ADRs:**
  - whether reassigning or setting P1 is `TEAM_LEAD`-only;
  - time-based escalation (not acknowledged within N minutes);
  - de-escalation back to the previous team.

### Alternatives considered
- **Individual assignee within the team:** rejected for v1. It needs on-call rules and duplicates what teams already organize themselves (ADR-0006).
- **Escalation as a notification plus marker, with a fixed owner:** rejected. Two teams could change the incident, and ownership was unclear.
- **`escalations` writes `team_id` and `priority` itself:** rejected. It breaks single-writer data ownership (ADR-0003) and would need two transactions for one change.
- **Separate `IncidentReassigned` and `IncidentPriorityRaised` events:** rejected. Every consumer would have to know which changes count as escalations; one event states it explicitly.

### Confirmation
- Unit tests on `Incident.escalate`:
  - it reassigns and raises priority;
  - it rejects the same team, a lower or equal priority, and a `RESOLVED` incident.
- An integration test with a policy (threshold SEV1, target team B) raises severity SEV2 → SEV1 and checks that:
  - the incident is owned by team B with priority P1;
  - exactly one `IncidentEscalated` was published;
  - each member of team B got a notification;
  - redelivering the severity event changes nothing.
- A Spring Modulith `verify()` test confirms that `escalations` changes incidents only through `IncidentApi`.
