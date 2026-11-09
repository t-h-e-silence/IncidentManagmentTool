# notification-service

All emails of the incident management system. An independent project: it shares no code with the other services;
it reads incident events and escalation email commands from RabbitMQ, asks organization-service who should be
emailed, and sends over SMTP. Architecture: ADR-0001 (D1, D8, D14) in the system's `docs/adr/`.

No HTTP API and no port, so any number of instances can run side by side:
`docker compose up -d --scale notification-service=3`. The delivery job locks rows with `FOR UPDATE SKIP LOCKED`, so
no email is sent twice.

## Owns

| What | Where |
|---|---|
| Database `notifications` (schema `notifications`): one row per email with its delivery state, processed message ids | Flyway, `src/main/resources/db/migration` |
| RabbitMQ quorum queues `notifications.urgent` and `notifications.normal`, their `.dlq`s, the `dlx` exchange | declared by `RabbitConfig` |
| Redis keys `notification-service:*` | cache of organization answers, 5 min |

## What it consumes, and who is emailed

| Message | Bound from | Emails |
|---|---|---|
| `incident.created` | `incidents.events` | the owning team; the active admins if it has no active members |
| `incident.status-changed`, `OPEN → IN_PROGRESS` | `incidents.events` | the reporter |
| `incident.status-changed` to `RESOLVED`, `CANCELLED`, or `RESOLVED → IN_PROGRESS` | `incidents.events` | the team and the reporter, with the reason |
| `incident.status-changed`, other steps | `incidents.events` | none |
| `notification.email-requested` | `notifications.commands` (escalation-service) | the one ready-made email it carries |

The reporter is never emailed about their own action, and nobody gets the same email twice. Repeated messages are
skipped by event id; unknown types and fields are ignored.

**Command `notification.email-requested`** (one per email; same envelope as the incident events, ADR D14):

```json
{ "eventId": "…", "type": "notification.email-requested", "version": 1, "occurredAt": "…", "correlationId": "…",
  "actor": { "id": "…", "name": "Dan Dba" }, "incidentId": "…",
  "data": { "reason": "INCIDENT_ESCALATED",
            "recipient": { "userId": "…", "name": "Bob Reporter", "email": "bob@example.com" },
            "subject": "[SEV1] Escalated: Checkout is slow", "body": "…", "severity": "SEV1" } }
```

`reason` is `INCIDENT_ESCALATED` or `INCIDENT_DEESCALATED`; `severity` is the new one; routing key
`notification.email-requested.<urgent|normal>`.

## Urgent and normal

Routing keys ending in `.urgent` (SEV1 / SEV2) land in `notifications.urgent`: 5 consumers per instance, prefetch 5,
and the first SMTP attempt right after the email is stored (measured: the email arrives within a second of the
request). `.normal` lands in `notifications.normal`: 2 consumers, prefetch 20; the delivery job sends it within
`notifications.delivery.interval` (10 s), urgent first.

**Failures:** an SMTP failure is retried after 1, 5 and 15 minutes, then the email is `DEAD_LETTERED`. A message
that cannot be processed is retried 3 times (1 s, 5 s, 25 s), logged at ERROR with its id and correlation id, and
moved to `<queue>.dlq`.

## Depends on

| What | How | If it is down |
|---|---|---|
| RabbitMQ | consumes its two queues | emails wait in the queues |
| organization-service | `GET /internal/teams/{id}`, `/internal/admins`, `/internal/users/{id}/recipient`, cached in Redis | cached answers keep working; otherwise the message is retried and, after 31 s, dead-lettered |
| SMTP server | sends | emails are retried (1 / 5 / 15 min) |
| Redis | cache | organization-service is asked directly |

## Settings

| Variable | Default |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/notifications`, `notifications`, `notifications` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `notification-service`, `notification-service` |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` |
| `ORGANIZATION_URL` | `http://localhost:8081` |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM` | `localhost`, `1025`, –, –, `incidents@example.com` |

## Build and run

```sh
./mvnw verify                       # tests (JDK 21)
./mvnw spring-boot:run              # needs its database, RabbitMQ, Redis, organization-service and an SMTP server
docker build -t incident-management/notification-service:local .
```
