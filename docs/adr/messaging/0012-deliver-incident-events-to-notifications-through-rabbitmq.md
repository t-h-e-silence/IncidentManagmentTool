# ADR-0012: Deliver incident events to notifications through RabbitMQ

## Status
Proposed — 2026-09-25. Implements the "future path" of [ADR-0008](0008-publish-domain-events-through-a-transactional-outbox.md) and refines [ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md).

## Context
Today the outbox relay dispatches events **in-process** to `notifications`, `audit` and `escalations` ([ADR-0008](0008-publish-domain-events-through-a-transactional-outbox.md)). The relay already works with several application instances (`FOR UPDATE SKIP LOCKED`), tracks each subscriber separately, and keeps events of one incident in order per subscriber. So a broker is not needed to scale the monolith.

The consumers are not alike:

| Consumer | Work per event | Load | Ordering per incident |
|---|---|---|---|
| `notifications` | One `Notification` per recipient, then calls to external channels (email, Slack, SMS) that are slow, rate-limited and sometimes down | Bursty: one incident → N recipients; a major outage → many incidents, plus escalations to a second team | Not needed |
| `audit` | One local insert | Low, steady | Not needed (entries are ordered by `occurredAt`) |
| `escalations` | Policy evaluation, one decision | Low | **Needed:** `SEV2→SEV1` and `SEV1→SEV2` processed out of order give the wrong transition. The relay orders them today; a queue with competing consumers and retry queues would not |

`notifications` is the one that needs independent scaling, broker-level retries and dead letters. It is also the first module expected to be extracted into a service. Moving every consumer at once would give up the relay's per-incident ordering for `escalations`, which would then rely only on its stale-version check ([ADR-0007](../architecture/0007-query-synchronously-publish-side-effects-asynchronously.md)), and would give nothing to `audit`.

## Decision
**Put RabbitMQ between the incidents outbox relay and `notifications` only.** `audit` and `escalations` keep in-process dispatch until they get their own reason to move (see *Later steps*).

**Topology** (all durable):

| Element | Name | Notes |
|---|---|---|
| Exchange | `incidents.events` (topic) | Owned by `incidents`, the publisher. |
| Routing keys | `incident.created`, `incident.status-changed`, `incident.severity-changed`, `incident.priority-changed`, `incident.escalated`, `incident.comment-added` | Every incident event is published, even if nobody consumes it yet. |
| Queue | `notifications.incident-events` | Bound to `incident.created`, `incident.status-changed`, `incident.escalated`. Owned by `notifications`, the consumer. |
| Retry queues | `notifications.incident-events.retry.10s` … `.retry.15m` | One queue per backoff step of [ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md) (10 s, 30 s, 1 min, 5 min, 15 min). Each has a fixed `x-message-ttl` and dead-letters back to the main queue. |
| Dead-letter exchange / queue | `notifications.dlx` → `notifications.incident-events.dlq` | Receives the message after the last attempt. |

**Publishing (relay):**
- The relay keeps reading `PENDING` outbox rows. For each row it publishes a **persistent** message with `mandatory = true`. The message has:
  - `message_id = eventId`;
  - `type`;
  - `correlation_id`;
  - a `schemaVersion` header;
  - the outbox `payload` as its JSON body.
- The broker is one more **subscriber** of the outbox ([ADR-0008](0008-publish-domain-events-through-a-transactional-outbox.md)). The `notifications` subscriber is replaced by `rabbitmq`, and its delivery row becomes `SENT` only after **publisher confirms**. The `audit` and `escalations` delivery rows are independent of it.
- A nack, a returned (unroutable) message or a timeout counts as a failed dispatch. That delivery row then follows the existing outbox retry and dead-letter rules ([ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md)). A repeated dispatch can publish or dispatch an event twice, which is safe because every consumer is idempotent.

**Consuming (`notifications`):**
- Manual acks and a prefetch of 20 per consumer.
- The consumer starts one DB transaction. It creates one `Notification` per recipient, idempotent by `UNIQUE(source_event_id, recipient_id)`, and commits. Only then does it **ack**. A crash before the ack causes a redelivery, which the unique key ignores.
- On a transient failure, the consumer republishes the message to the next retry queue with publisher confirms, sets its own `x-attempt` header and acks the original. It does not use `x-death`, which counts per queue and gets complicated with several retry queues.
- A permanent failure (e.g. a payload that cannot be deserialized) goes straight to the DLX ([ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md)), and so does a message after the last retry step. Each dead-lettered message:
  - is logged at ERROR with `eventId` and `correlationId`;
  - increments `outbox_dead_lettered_total{subscriber="notifications"}`, which is alerted on like any other subscriber;
  - can be replayed by moving it from the `.dlq` queue back to the main queue.

**Which retries live where** (clarifies [ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md)):

| Failure | Retried by |
|---|---|
| Broker unreachable when publishing | Outbox row (`RETRYING`, DB) |
| `notifications` fails to *process the event*, e.g. `membersOf` fails or the DB is down | RabbitMQ retry queues → DLQ |
| An external channel fails to *deliver one notification* | `Notification` row (`RETRYING`, DB), unchanged |

Channel delivery stays in the database. Each `Notification` has its own status, attempts and replay, and one failing recipient must not re-run the whole event.

**Unchanged:**
- the outbox is kept, because publishing inside the incident transaction would be a dual write;
- event schemas stay owned by the publisher ([ADR-0007](../architecture/0007-query-synchronously-publish-side-effects-asynchronously.md));
- consumers stay idempotent;
- domain code does not change. Only the relay gets a RabbitMQ publisher adapter, and `notifications` gets a listener adapter (Spring AMQP).

**When to switch it on.** The adapters sit behind configuration (`events.transport.notifications = in-process | rabbitmq`). Switch to `rabbitmq` at the first of:
1. a real notification channel replaces the stub;
2. `notifications` is extracted into its own service;
3. a consumer outside the process needs incident events.

**Later steps (each needs its own ADR):**
- **`audit`** gets a queue `audit.incident-events` when it is extracted or feeds a search copy. It needs no ordering.
- **`escalations`** gets a queue only with `x-single-active-consumer = true` and retries that keep order (no separate retry queues). Its stale-version check stays in place as a second safeguard.
- **Other publishers.** `escalations` and `organization` get their own exchanges (`escalations.events`, `organization.events`) once their events are consumed outside the process.
- **The escalation step** (`escalations` → `IncidentApi.escalate`, [ADR-0011](../incidents/0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md)) stays a **synchronous call** and becomes REST after extraction, not a message. `incidents` must validate and answer, and the escalations outbox already retries that call.

## Consequences
- **Easier:**
  - the notification workload scales separately: add consumers without touching incident creation;
  - `notifications` can be extracted without changing `incidents`;
  - retries and dead letters for event processing come from the broker, not from our own scheduling code;
  - DLQ depth shows event-processing problems at a glance;
  - new consumers (search copy, AI assistant) bind a queue with no publisher change.
- **Harder:**
  - one more piece of infrastructure to run, monitor (queue depth, DLQ depth, unacked messages) and secure;
  - two transports exist side by side until `audit` and `escalations` move;
  - `notifications` loses the relay's per-incident ordering (acceptable: its notifications are independent of each other);
  - five retry queues per consumer queue to declare and keep in sync with [ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md);
  - local development and integration tests need RabbitMQ: add it to `docker-compose` and use Testcontainers.

## Alternatives considered
- **Move all consumers to RabbitMQ at once:** rejected for now. `escalations` would lose the relay's ordering, and `audit` gains nothing while it is a local insert.
- **Publish to RabbitMQ inside the incident transaction, without the outbox:** rejected. A broker outage or a rollback after publishing would lose events or publish events for incidents that don't exist (dual write).
- **Kafka:** rejected. We need work queues, per-consumer retries and dead letters, not a replayable log or partition ordering. It is also heavier to operate, and the brief names RabbitMQ.
- **RabbitMQ delayed-message plugin instead of retry queues:** rejected. It is a plugin that must be installed on every node, and its delayed messages are not replicated. Plain TTL queues work on any RabbitMQ.
- **Keep in-process dispatch forever:** rejected, because it prevents extracting `notifications`. It remains the default until one of the switch-on triggers happens.

## Confirmation
Integration tests with Testcontainers PostgreSQL + RabbitMQ:
- Creating an incident publishes one confirmed message to `incidents.events`. The `rabbitmq` delivery row becomes `SENT`, and a `Notification` exists for each team member.
- Publishing the same event twice creates the notifications only once.
- With `membersOf` stubbed to always fail, the message passes through all retry queues and ends in `notifications.incident-events.dlq`, and the dead-letter metric increases by 1.
- With the broker stopped, incident creation still returns 201. The `rabbitmq` delivery row is `RETRYING` until the broker is back, and the `audit` and `escalations` deliveries are `SENT` meanwhile.
- A Spring Modulith test confirms that only the relay adapter and the `notifications` listener depend on Spring AMQP.
