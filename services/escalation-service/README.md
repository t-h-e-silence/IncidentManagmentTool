# escalation-service

Records escalations and de-escalations and writes their emails. An independent project: it shares no code with the
other services; it reads incident-service's events from RabbitMQ, asks organization-service for team names and
members, and hands the emails to notification-service as commands. Architecture: ADR-0001 (D2, D4, D14) in the
system's `docs/adr/`.

The decision itself (severity must go up or down, hand-over, target team must be active) is made atomically in
incident-service; this service only reacts to it. No HTTP API and no port.

## Owns

| What | Where |
|---|---|
| Database `escalations` (schema `escalations`): escalation records (append-only), the outbox, processed event ids | Flyway, `src/main/resources/db/migration` |
| RabbitMQ direct exchange `notifications.commands` and the `unrouted` alternate exchange with its queue | declared by `RabbitConfig` |
| RabbitMQ quorum queue `escalations.incident-events` (bound to `incident.escalated.*`, `incident.de-escalated.*`), its `.dlq`, the `dlx` exchange | declared by `RabbitConfig` |
| Redis keys `escalation-service:*` | cache of organization answers, 5 min |

## What it consumes and publishes

Consumes `incident.escalated` and `incident.de-escalated` (see incident-service's README). For each, in one
transaction: the escalation record, one `notification.email-requested` command per email, and the processed-event
mark. Repeats are skipped by event id.

| Receiver | Email |
|---|---|
| owning team after the change (the active admins if it has no active members) | "Escalated to you" on a hand-over, otherwise "Escalated" / "De-escalated", with who, why and the severity change |
| previous team, only on a hand-over | "Handed over to …: your team no longer owns this incident" |
| reporter | "Your incident was escalated / de-escalated" |

Everyone gets at most one email (owning team first, then previous team, then reporter); the person who escalated gets
none.

**Publishes** `notification.email-requested` to `notifications.commands` through the outbox (stored with the record,
relayed after commit, marked sent on RabbitMQ's confirm). Routing key `notification.email-requested.<urgent|normal>`,
urgent when the new severity is SEV1 or SEV2. The command keeps the escalating request's `correlationId`:

```json
{ "eventId": "…", "type": "notification.email-requested", "version": 1, "occurredAt": "…", "correlationId": "…",
  "actor": { "id": "…", "name": "Dan Dba" }, "incidentId": "…",
  "data": { "reason": "INCIDENT_ESCALATED",
            "recipient": { "userId": "…", "name": "Carol Platform", "email": "carol@example.com" },
            "subject": "[SEV1] Escalated to you: Primary DB slow", "body": "…", "severity": "SEV1" } }
```

## Depends on

| What | How | If it is down |
|---|---|---|
| RabbitMQ | consumes its queue, publishes commands | events wait in the queue; commands wait in the outbox |
| organization-service | `GET /internal/teams/{id}`, `/internal/admins`, `/internal/users/{id}/recipient`, cached in Redis | cached answers keep working; otherwise the event is retried and, after 31 s, dead-lettered |
| Redis | cache | organization-service is asked directly |

## Settings

| Variable | Default |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/escalations`, `escalations`, `escalations` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `escalation-service`, `escalation-service` |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` |
| `ORGANIZATION_URL` | `http://localhost:8081` |

## Build and run

```sh
./mvnw verify                       # tests (JDK 21)
./mvnw spring-boot:run              # needs its database, RabbitMQ, Redis and organization-service
docker build -t incident-management/escalation-service:local .
```
