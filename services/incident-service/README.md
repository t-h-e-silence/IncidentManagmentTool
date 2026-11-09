# incident-service

The incident lifecycle and comments of the incident management system. An independent project: it shares no code
with the other services; it asks organization-service over HTTP and tells everyone else through RabbitMQ events.
Architecture: ADR-0001 (D1–D4, D6, D9, D14) in the system's `docs/adr/`.

## Owns

| What | Where |
|---|---|
| Database `incidents` (schema `incidents`): incidents, comments, the outbox, idempotency keys | Flyway, `src/main/resources/db/migration`; `V4__demo_data.sql` adds three demo incidents |
| RabbitMQ exchange `incidents.events` (topic) and the `unrouted` alternate exchange with its queue | declared by `RabbitConfig` |
| Redis keys `incident-service:*` | cache of organization answers, 5 min |

## API

| Endpoint | Notes |
|---|---|
| `GET /incidents`, `GET /teams/{teamId}/incidents`, `GET /users/{user}/incidents` | lists, newest first |
| `POST /incidents` | create; optional `Idempotency-Key` |
| `PATCH /incidents/{id}` | title, description, severity (escalation / de-escalation), status; optional `Idempotency-Key` |
| `GET /incidents/{id}`, `POST /incidents/{id}/comments` | view; comment (optional `Idempotency-Key`) |

Caller in `X-User-Id`. Full description: `openapi.yaml`, or Swagger UI at http://localhost:8082/swagger-ui.html.

## Events it publishes

To `incidents.events`, through the outbox (stored with the change, relayed after commit, marked sent on RabbitMQ's
confirm). Routing key `incident.<event>.<urgent|normal>`, urgent for SEV1/SEV2; JSON envelope as in ADR D14.

| Event | When | `data` besides `incident` (snapshot after the change) |
|---|---|---|
| `incident.created` | POST /incidents | `description`, `categoryId` |
| `incident.details-updated` | title / description changed | `fromTitle`, `toTitle`, `descriptionChanged` |
| `incident.escalated` / `incident.de-escalated` | severity raised / lowered | `fromSeverity`, `toSeverity`, `fromTeamId`, `toTeamId`, `reason` |
| `incident.status-changed` | status changed | `fromStatus`, `toStatus`, `note` |
| `incident.comment-added` | comment added | `commentId`, `text` |

## Depends on

| What | How | If it is down |
|---|---|---|
| organization-service | `GET /internal/**`, cached in Redis | cached callers keep working; otherwise 503 |
| RabbitMQ | publishes events | events wait in the outbox and are sent when it is back |
| Redis | cache | organization-service is asked directly |

## Settings

| Variable | Default |
|---|---|
| `PORT` | `8082` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/incidents`, `incidents`, `incidents` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `incident-service`, `incident-service` |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` |
| `ORGANIZATION_URL` | `http://localhost:8081` |

## Build and run

```sh
./mvnw verify                       # tests (JDK 21)
./mvnw spring-boot:run              # needs its database, RabbitMQ, Redis and organization-service
docker build -t incident-management/incident-service:local .
```
