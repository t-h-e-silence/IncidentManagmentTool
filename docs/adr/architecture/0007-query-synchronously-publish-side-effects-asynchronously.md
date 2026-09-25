# ADR-0007: Query synchronously, publish side effects asynchronously

## Status
Proposed — 2026-09-25

## Context
Modules need to cooperate, in three ways:
- **Queries:** some interactions produce an answer the request can't finish without. Which team handles this category? Is this user a member?
- **Side effects:** others are reactions to something that already happened (notify, audit, evaluate escalation).
- **Commands:** occasionally a module must ask the **owner** of some data to change it, e.g. `escalations` asks `incidents` to reassign an incident ([ADR-0011](../incidents/0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md)).

We need one consistent rule for all three.

## Decision
- **Queries are synchronous** in-process calls to another module's `api`, used when the caller *needs the answer to finish the request*:
  - `incidents → organization.teamForCategory`, `roleOf`, and the target-team check on escalation;
  - `notifications → organization.membersOf`, `userById`;
  - `escalations → organization` (policy target team exists and is not archived).
- **Commands to another module are synchronous calls to the owner's `api`,** never direct writes. When they are triggered by an event, they run from the caller's outbox, so they are retried ([ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md)) and must be **idempotent** (a command id such as `decisionId`).
  - Only one exists today: `escalations → IncidentApi.escalate`.
- **Side effects are asynchronous** domain events:

  | Event | Publisher | Consumers |
  |---|---|---|
  | `IncidentCreated` | incidents | notifications, audit, escalations |
  | `IncidentStatusChanged` | incidents | notifications, audit, escalations |
  | `IncidentSeverityChanged` | incidents | escalations, audit |
  | `IncidentPriorityChanged` | incidents | audit |
  | `IncidentEscalated` | incidents ([ADR-0011](../incidents/0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md)) | notifications, audit, escalations |
  | `CommentAdded` | incidents | audit |
  | configuration events (proposed), e.g. `MemberAdded`, `CategoryRerouted`, `EscalationPolicyChanged` | organization, escalations | audit |
  | `TeamArchived` (proposed) | organization | escalations, audit |

- **Event schemas are owned by the publisher** (`<module>.api.events`) and evolve by the rules in [ADR-0014](0014-evolve-event-schemas-additively-with-a-schema-version.md). Every event carries `eventId`, `occurredAt`, `actorId`, `correlationId`, `schemaVersion`, and for events about an aggregate also `aggregateId` and `aggregateVersion` (the aggregate's optimistic-lock version after the change).
- **Delivery is at-least-once and not globally ordered.** Every consumer is **idempotent by a unique key in its own schema**, e.g.:
  - `processed_events(event_id)` in escalations;
  - `UNIQUE(source_event_id, recipient_id)` on `notification`;
  - `UNIQUE(event_id)` on `audit_entry`.

  A consumer that depends on order compares `aggregateVersion` with the last version it has seen and ignores older events.

## Consequences
- **Easier:**
  - requests are fast and never fail because of side effects;
  - publishers don't know their consumers, so new consumers (e.g. the future AI assistant) need no publisher change;
  - it maps directly to RabbitMQ later ([ADR-0012](../messaging/0012-deliver-incident-events-to-notifications-through-rabbitmq.md));
  - only the owner ever writes its data, even for changes triggered by another module.
- **Harder:**
  - eventual consistency: notifications and audit entries appear shortly after the commit;
  - duplicate and stale-event handling adds code;
  - after extraction, an `organization` outage blocks incident creation (mitigation: a cached routing table);
  - after extraction, commands become REST calls, which need their own timeouts and retries.
- **Follow-on ADRs:** event delivery mechanism ([ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md)), failure handling ([ADR-0009](../messaging/0009-retry-failed-deliveries-and-dead-letter-them.md)), event versioning ([ADR-0014](0014-evolve-event-schemas-additively-with-a-schema-version.md)).

## Alternatives considered
- **Everything synchronous:** rejected. A notification outage would block incident creation.
- **Everything asynchronous, including routing:** rejected. The reporter needs an immediate, consistent answer about which team owns the incident.
- **Commands as messages (e.g. an `EscalateIncident` command event):** rejected. The caller needs to know whether the owner accepted the change, and a message adds a second outbox hop for no gain inside one process.

## Confirmation
- A Spring Modulith `verify()` test enforces the allowed dependencies (**not in place yet**, see [ADR-0001](0001-use-a-modular-monolith.md)).
- Consumer tests deliver the same event twice and assert a single effect.
- An `escalations` test delivers `SEV2→SEV1` (version 3) after `SEV1→SEV2` (version 4) and asserts no escalation.
