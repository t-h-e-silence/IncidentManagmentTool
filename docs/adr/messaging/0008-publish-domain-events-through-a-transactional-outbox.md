# ADR-0008: Publish domain events through a transactional outbox

## Status
Proposed — 2026-09-25. Renamed from "Deliver notifications outside the incident transaction": the outbox serves every event of every publishing module, not only notifications.

## Context
When an incident is created, responders of the owning team must be notified. Notification channels (email over SMTP today, [ADR-0016](0016-send-notifications-by-email-over-smtp-without-a-message-broker.md)) are external, slow and sometimes down. If delivery happened inside the incident transaction:
- a channel outage would block or roll back incident creation, exactly when things are broken;
- a rollback *after* sending would notify people about an incident that doesn't exist.

At the same time, a created incident must **never be silently left unnotified**. Sending only after commit, with no durable record, loses the event if the application crashes in between.

The same problem applies to every event that other modules react to (audit, escalations), and to commands triggered by events ([ADR-0007](../architecture/0007-query-synchronously-publish-side-effects-asynchronously.md)). Two more properties are needed:
- **Failure isolation:** one failing consumer, e.g. a bug in `audit`, must not delay or dead-letter the event for the others.
- **Order per incident:** `escalations` must not see `SEV2→SEV1` after `SEV1→SEV2` for the same incident. Retries with backoff and several relay instances would otherwise reorder events.

## Decision
- **Transactional outbox per publishing module.** In one transaction, the module saves its aggregate and one row per event in `<module>.outbox`:
  - `event_id`, `type`, `payload`, `correlation_id`;
  - `aggregate_id` and `aggregate_version`;
  - `occurred_at`.

  This is the only extra work in the business transaction. Notifications, audit and escalation evaluation all happen after commit.
- **One delivery row per subscriber.** In the same transaction, the module writes one `outbox_delivery` row per subscriber of that event type (`audit`, `escalations`, `notifications`):
  - `event_id`, `subscriber`, `status`, `attempts`, `next_attempt_at`, `last_error`;
  - `status` is `PENDING`, `RETRYING`, `SENT` or `DEAD_LETTERED`.

  Each subscriber succeeds, retries and dead-letters on its own ([ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md)).
- **Outbox relay.** A scheduled poller picks due delivery rows (`FOR UPDATE SKIP LOCKED`), dispatches each to its subscriber in-process and marks it `SENT` on success.
- **Order per aggregate and subscriber.** The relay does not dispatch a delivery while an **earlier** delivery (lower `aggregate_version`) for the same `(subscriber, aggregate_id)` is still `PENDING` or `RETRYING`.
  - A `DEAD_LETTERED` delivery stops blocking, so one poison event doesn't freeze an incident forever.
  - Consumers that depend on order also ignore stale versions ([ADR-0007](../architecture/0007-query-synchronously-publish-side-effects-asynchronously.md)), so a dead letter or a replay can't apply an old transition.
- **Notification intent.** `notifications` consumes the event idempotently and creates one `Notification` per active member of the relevant team. A delivery worker in `notifications` then sends each one by **email over SMTP** ([ADR-0016](0016-send-notifications-by-email-over-smtp-without-a-message-broker.md)); a stub channel that logs the message and can be configured to fail is used locally and in tests.
- **No silent zero-recipient case.** [ADR-0015](../data/0015-deactivate-or-archive-referenced-data-instead-of-deleting-it.md) prevents a team from losing its last active member. This rule is the safety net if `membersOf` still returns no active members, e.g. after users are disabled in the identity provider or data is fixed by hand:
  - the notification goes to all active `ADMIN`s instead, who can reassign the incident or fix the team;
  - a WARN log is written, and `notifications_no_recipients_total` is incremented.

**Failure behavior in the first implementation:**

| Failure | Result |
|---|---|
| Channel down during delivery | Incident already committed; delivery is retried ([ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md)) |
| One subscriber keeps failing | Only its delivery row retries and dead-letters; the other subscribers are unaffected |
| Crash after commit, before dispatch | Delivery rows still `PENDING`; dispatched after restart |
| Crash after dispatch, before marking `SENT` | Event redelivered to that subscriber; it ignores the duplicate by its unique key |
| Business transaction fails | Neither the aggregate change nor the event exists; nothing is sent |
| Team has no active members | Active admins are notified; the metric shows it |

## Consequences
- **Easier:**
  - incident creation never depends on notification channels or on any consumer;
  - no lost events: each one is persisted before it's dispatched;
  - dead letters and metrics are per subscriber (`outbox_dead_lettered_total{subscriber}`), so you can see which consumer is broken;
  - per-incident order makes escalation evaluation predictable;
  - a message broker can be put behind the relay later without dual writes, if a module is ever extracted.
- **Harder:**
  - notifications are delayed by the polling interval (seconds);
  - more moving parts: outbox and delivery tables, poller, idempotency keys;
  - adding a subscriber means registering it with the publisher's relay; existing events are not backfilled;
  - a failing delivery holds back later deliveries of the same incident for the same subscriber, for up to its retry window;
  - a recipient may rarely get a duplicate message from the external channel (accepted).
- **Follow-on ADRs:** retry and dead-letter policy ([ADR-0009](0009-retry-failed-deliveries-and-dead-letter-them.md)); email delivery without a broker ([ADR-0016](0016-send-notifications-by-email-over-smtp-without-a-message-broker.md)).

## Alternatives considered
- **Send inside the transaction:** rejected (see Context).
- **`@TransactionalEventListener(AFTER_COMMIT)` without an outbox:** simpler, but the event is lost if the application crashes after commit.
- **One status per outbox row for all subscribers:** rejected. One failing consumer delays and dead-letters the event for all of them, and the metric can't tell which consumer failed.
- **Spring Modulith event publication registry:** considered seriously. It is a built-in outbox that already tracks completion per listener and can externalize events to AMQP. Rejected for now because:
  - it has no backoff schedule or `DEAD_LETTERED` state, only "incomplete" publications that are resubmitted;
  - it has no per-aggregate ordering.

  Revisit if a later version adds these, since it would remove our relay code.
- **Put a message broker (RabbitMQ) behind the outbox:** rejected; the relay already covers ordering, retries and several instances ([ADR-0016](0016-send-notifications-by-email-over-smtp-without-a-message-broker.md)).

## Confirmation
Integration tests (Testcontainers):
- creating an incident while the stub channel fails still returns 201 and leaves `PENDING` delivery rows;
- after the relay runs, a `Notification` exists for each active team member;
- with the `audit` subscriber failing, notifications are still created and only the `audit` delivery is `RETRYING`;
- two severity changes of one incident are dispatched to `escalations` in version order even when the first one fails once;
- a team whose members are all deactivated notifies the active admins and increments `notifications_no_recipients_total`.
