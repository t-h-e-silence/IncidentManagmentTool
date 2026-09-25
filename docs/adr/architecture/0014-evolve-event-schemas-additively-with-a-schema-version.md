# ADR-0014: Evolve event schemas additively with a schema version

## Status
Proposed — 2026-09-25

## Context
Every event carries a `schemaVersion` ([ADR-0007](0007-query-synchronously-publish-side-effects-asynchronously.md)), but nothing said when it changes or what consumers must do. Events are not short-lived method calls. They live on:
- in `outbox` rows that are retried or replayed ([ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md), [ADR-0009](../messaging/0009-retry-failed-deliveries-and-dead-letter-them.md));
- in RabbitMQ queues and dead-letter queues ([ADR-0012](../messaging/0012-deliver-incident-events-to-notifications-through-rabbitmq.md));
- as audit payloads ([ADR-0010](../audit/0010-make-the-audit-log-append-only.md)).

After extraction, publisher and consumer are deployed separately. A change that breaks consumers would fail silently in the dead-letter queue, or break replays of old events.

## Decision
- **Additive changes keep the version.** Adding an optional field, or a new enum value that consumers may ignore, does not change `schemaVersion`.
  - Consumers must **ignore unknown fields**: Jackson `FAIL_ON_UNKNOWN_PROPERTIES = false` for event deserialization.
  - Consumers must treat a missing new field as "unknown", never as an error.
- **Breaking changes bump the version.** Removing or renaming a field, changing its type or meaning, or making it required are breaking changes.
  - The publisher publishes **both** versions for a transition period: two outbox rows with the same `aggregateVersion` but different `eventId` and `schemaVersion`.
  - Each consumer subscribes to the versions it understands.
  - The old version is dropped only when no consumer uses it **and** no undelivered or dead-lettered delivery still holds it.
- **Event types are never reused** for a different meaning. A different fact gets a new type name.
- **The schema lives with the publisher.** The event records in `<module>.api.events` are the contract. Each version has a JSON example in `src/test/resources/events/<Type>.v<N>.json`.
- **Audit keeps the original payload** with its version. The timeline renders old versions as they are and never migrates them ([ADR-0010](../audit/0010-make-the-audit-log-append-only.md)).

## Consequences
- **Easier:**
  - publishers can add information without coordinating with every consumer;
  - replays and dead-letter recovery keep working across deployments;
  - consumers see from `schemaVersion` what they are reading.
- **Harder:**
  - a breaking change needs a transition period with double publishing;
  - consumers must be written defensively (optional fields, unknown enum values);
  - JSON examples must be maintained per version.

## Alternatives considered
- **No versioning, change events freely:** rejected. Events persisted in the outbox, in queues and in audit would become unreadable after a deploy.
- **Schema registry (Avro or Protobuf with Confluent/Apicurio):** rejected for now. It adds infrastructure and a binary format while all modules are Java in one repository. Revisit if non-Java consumers appear.
- **Upcasting old events to the newest version inside consumers:** kept as an option for a consumer that must read many old versions (e.g. audit rendering), but not required.

## Confirmation
- A contract test per event type deserializes every stored JSON example (`<Type>.v<N>.json`) with the current consumer code.
- A test adds an unknown field to each example and asserts that deserialization still succeeds.
