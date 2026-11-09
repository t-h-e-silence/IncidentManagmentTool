# audit-service

The append-only history of every incident, built from incident events. An independent project: it shares no code
with the other services; it reads incident-service's events from RabbitMQ and asks organization-service who the
caller is. Architecture: ADR-0001 (D1, D3, D4, D8, D14) in the system's `docs/adr/`.

## Owns

| What | Where |
|---|---|
| Database `audit` (schema `audit`): audit entries (append-only, enforced by a trigger), processed event ids | Flyway, `src/main/resources/db/migration`; `V3__demo_data.sql` adds the history of the demo incidents |
| RabbitMQ quorum queue `audit.incident-events` (bound to `incident.#`), its `audit.incident-events.dlq` and the `dlx` exchange | declared by `RabbitConfig` |
| Redis keys `audit-service:*` | cache of organization answers, 5 min |

## API

| Endpoint | Notes |
|---|---|
| `GET /incidents/{id}/history` | oldest first; 404 if nothing was recorded for the incident (yet) |

Caller in `X-User-Id`. Full description: `openapi.yaml`, or Swagger UI at http://localhost:8083/swagger-ui.html.

## Events it consumes

Every `incident.*` event of incident-service (see its README). Each becomes one entry (`INCIDENT_CREATED`,
`DETAILS_UPDATED`, `ESCALATED`, `DE_ESCALATED`, `STATUS_CHANGED`, `COMMENT_ADDED`). Repeats are skipped by event id;
unknown event types and unknown fields are ignored. A message that keeps failing is retried 3 times (1 s, 5 s, 25 s)
and then moved to `audit.incident-events.dlq`.

## Depends on

| What | How | If it is down |
|---|---|---|
| RabbitMQ | consumes `audit.incident-events` | the history falls behind and catches up when it is back |
| organization-service | `GET /internal/**` for the caller, cached in Redis | cached callers keep working; otherwise 503 |
| Redis | cache | organization-service is asked directly |

## Settings

| Variable | Default |
|---|---|
| `PORT` | `8083` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/audit`, `audit`, `audit` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | `localhost`, `5672`, `audit-service`, `audit-service` |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` |
| `ORGANIZATION_URL` | `http://localhost:8081` |

## Build and run

```sh
./mvnw verify                       # tests (JDK 21)
./mvnw spring-boot:run              # needs its database, RabbitMQ, Redis and organization-service
docker build -t incident-management/audit-service:local .
```
