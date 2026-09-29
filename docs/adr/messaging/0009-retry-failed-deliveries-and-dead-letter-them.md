# ADR-0009: Retry failed deliveries and dead-letter them

## Status
Proposed — 2026-09-25. Refined by [ADR-0016](0016-send-notifications-by-email-over-smtp-without-a-message-broker.md): how SMTP errors are classified. All retries run in the database; there is no message broker.

## Context
With the outbox ([ADR-0008](0008-publish-domain-events-through-a-transactional-outbox.md)), events are never lost. Two kinds of delivery can still fail repeatedly:
- **dispatching an event** to a subscriber, e.g. a bug in the consumer or its database is down;
- **delivering one notification** to a channel, e.g. the channel is down or the address is invalid.

Retrying forever hides problems and wastes resources. Dropping deliveries after a failure breaks the "never silently unnotified" guarantee. Not all failures are alike, though: a channel that is down will recover, but an invalid address will not. And a lost SEV1 notification is far more urgent than a lost SEV4 one.

## Decision
- **What is retried.**
  - Each `outbox_delivery` row, i.e. one event for one subscriber ([ADR-0008](0008-publish-domain-events-through-a-transactional-outbox.md)).
  - Each `Notification`, i.e. one recipient.
  - A failure never retries the other subscribers or the other recipients.
- **Transient vs permanent failures.**
  - Transient failures are retried: timeouts, connection errors, HTTP 5xx or 429, SMTP `4xx`, and database unavailable. A 429 respects `Retry-After`.
  - Permanent failures are dead-lettered **immediately**, without retries. Examples: invalid or unknown address (SMTP `5xx` to `RCPT TO`), HTTP 4xx other than 429, a payload that cannot be deserialized.
  - The channel adapter classifies the error with a `permanent` flag on the existing `DeliveryFailedException`.
- **Backoff:** 6 attempts: the first one, then 5 retries after about 10 s, 30 s, 1 min, 5 min and 15 min, each delay with ±20 % jitter. The status is `RETRYING` in between. The last attempt happens about 21 minutes after the first.
- **Dead letter.** After the last attempt, or at once for a permanent failure, the status becomes `DEAD_LETTERED`. The record is kept, and:
  - an ERROR log is written with `eventId` or `notificationId`, `subscriber`, `correlationId` and the last error;
  - `outbox_dead_lettered_total{subscriber}` or `notifications_dead_lettered_total{severity, channel}` is incremented;
  - it can be **replayed manually** by resetting it to `PENDING` (`NotificationApi.replay`, and an admin endpoint for outbox deliveries).
- **Alerting.** Alert rules on the dead-letter metrics:
  - any dead-lettered notification of a **SEV1 or SEV2** incident pages the platform on-call at once, because a responder may not know about a critical incident;
  - other dead letters raise a ticket-level alert and are reviewed daily;
  - any `audit` dead letter raises a ticket-level alert, because it leaves a gap in the audit trail ([ADR-0010](../audit/0010-make-the-audit-log-append-only.md)).
- **Where retries live.** Both kinds are rows in PostgreSQL: `outbox_delivery` for events, `notification` for emails. There is no broker-level retry ([ADR-0016](0016-send-notifications-by-email-over-smtp-without-a-message-broker.md)). For email, SMTP `4xx` replies and connection errors are transient, and a `5xx` reply to a recipient is permanent.

## Consequences
- **Easier:**
  - transient failures recover automatically;
  - permanent failures surface in seconds instead of after 21 minutes;
  - dead letters are visible, measurable per subscriber, channel and severity, and replayable;
  - critical incidents cannot be silently unnotified: at worst the platform on-call is paged.
- **Harder:**
  - dead-lettered items need operational attention: the on-call rotation, the alert rules and a replay procedure;
  - channel adapters must classify errors correctly; a misclassified permanent error still retries for 21 minutes (acceptable);
  - retry state adds columns and scheduler logic.

## Alternatives considered
- **Retry forever:** rejected because it hides poison messages and blocks the queue.
- **Log and drop after the first failure:** rejected because notifications would be lost silently.
- **Longer retry schedule for SEV1/SEV2:** rejected. After 21 minutes a person must look at it anyway, and paging on the dead letter gets there faster than more retries.
- **Same handling for all errors:** rejected. It delays the response to a bad address by 21 minutes for nothing.

## Confirmation
Integration tests with the stub channel:
- set to fail transiently: after 6 attempts the notification is `DEAD_LETTERED` and `notifications_dead_lettered_total` increases by 1; a replay delivers it once the stub recovers;
- set to fail permanently: the notification is `DEAD_LETTERED` after one attempt;
- with the `audit` subscriber failing: only its delivery row dead-letters, with `subscriber="audit"` on the metric.
