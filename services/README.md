# Incident management: microservices

The microservices version of the incident management monolith, as decided in
[ADR-0001](docs/adr/0001-migrate-to-microservices.md) ([diagrams](docs/adr/0001-migrate-to-microservices-diagrams.md)).
This folder does not depend on the monolith: it can be moved out as a whole, and **each service is an independent
project** that can be moved to its own repository and built, tested and released alone.

| Folder | What | Port | Status |
|---|---|---|---|
| `organization-service` | Users, teams, category routing: `GET /teams` + `/internal/**` for the other services | 8081 | done |
| `incident-service` | Incident endpoints, idempotency keys; publishes every change to `incidents.events` through an outbox | 8082 | done |
| `audit-service` | History built from incident events, `GET /incidents/{id}/history` | 8083 | done |
| `notification-service` | All emails, from incident events and escalation commands; urgent / normal queues; no HTTP API, scalable | – | done |
| `escalation-service` | Escalation records; asks notification-service for the escalation emails; no HTTP API | – | done |

System-level files, which wire the services together: `docker-compose.yml`, `postgres/` (one database and DB user
per service), `rabbitmq/` (vhost, one RabbitMQ user per service), `postman/` (smoke test), `docs/` (ADR),
`export-openapi.sh`.

## Run

```sh
docker compose up -d --build        # everything: the 5 services, Postgres (5 databases, host port 5434), RabbitMQ, Redis, Mailpit
curl -H 'X-User-Id: dan' http://localhost:8081/teams
curl -X POST http://localhost:8082/incidents -H 'X-User-Id: bob' -H 'Content-Type: application/json' \
     -d '{"title": "Checkout is slow", "categoryId": "30000000-0000-0000-0000-000000000003", "severity": "SEV2"}'
curl -H 'X-User-Id: alice' http://localhost:8083/incidents/<id>/history    # a second or two later
docker compose down                 # stop (keeps the data); `down -v` also wipes it
```

There is **no API gateway** (ADR D13): clients call each service on its own port, with the monolith's paths and
bodies.

| Endpoints | Service | URL |
|---|---|---|
| `GET /teams` | organization-service | http://localhost:8081 |
| `GET /incidents`, `GET /teams/{id}/incidents`, `GET /users/{user}/incidents`, `POST /incidents`, `PATCH /incidents/{id}`, `GET /incidents/{id}`, `POST /incidents/{id}/comments` | incident-service | http://localhost:8082 |
| `GET /incidents/{id}/history` | audit-service | http://localhost:8083 |

| What | Where |
|---|---|
| RabbitMQ management | http://localhost:15672 (`admin` / `admin`), vhost `incident-management` |
| Emails (Mailpit) | http://localhost:8025 |
| Redis | `docker compose exec redis redis-cli --scan` (keys are prefixed per service) |

## Swagger

Each service documents its own endpoints. Click **Authorize** and enter a username (e.g. `dan`) for `X-User-Id`.

| Service | Swagger UI | Exported spec |
|---|---|---|
| organization-service | http://localhost:8081/swagger-ui.html | [`organization-service/openapi.yaml`](organization-service/openapi.yaml): `GET /teams` + `/internal/**` |
| incident-service | http://localhost:8082/swagger-ui.html | [`incident-service/openapi.yaml`](incident-service/openapi.yaml) |
| audit-service | http://localhost:8083/swagger-ui.html | [`audit-service/openapi.yaml`](audit-service/openapi.yaml) |

The exported files are copies of `/v3/api-docs.yaml`. After changing an endpoint, rebuild the stack and run
`./export-openapi.sh` to keep them in sync.

**Demo data** comes from an ordinary Flyway migration in each service, so every fresh database has it:

| Service | Migration | Data (fixed ids) |
|---|---|---|
| organization | `V3__demo_data.sql` | users `ada` (admin), `alice`, `bob`, `carol`, `dan`, `erin` (`20000000-…-000N`); teams Platform, Database, Network (`10000000-…`); categories (`30000000-…`) |
| incident | `V4__demo_data.sql` | incidents `40000000-…-0001` OPEN (Database), `…0002` IN_PROGRESS (Network), `…0003` RESOLVED (Platform); a comment |
| audit | `V3__demo_data.sql` | the history of those three incidents |

The demo rows publish no events, so they send no emails.

## Postman

`postman/` holds a collection for every public endpoint plus organization's `/internal` API, and checks the emails in
Mailpit. It has assertions on every request, so it is also the smoke test of ADR D12.

1. Import `IncidentManagement-services.postman_collection.json` and both environments.
2. Pick the **services-local** environment: one URL per service (`organizationUrl`, `incidentUrl`, `auditUrl`,
   `internalUrl`, `mailpitUrl`); change them there if the services run elsewhere.
3. Send single requests, or *Run collection* for all 29 in order.

Users (`dan`, `bob`, …) and the demo ids are collection variables. `incidentId` and `idempotencyKey` are set while it
runs; `historyDelayMs` is how long it waits before reading a history built from events. The demo incidents are only
read, so it can run again and again. From the command line:

```sh
docker run --rm --network host -v "$PWD/postman":/etc/newman postman/newman:6-alpine \
  run IncidentManagement-services.postman_collection.json -e services-local.postman_environment.json
```

The monolith's own collections use a single base URL, so they need a gateway; they are not used here.

**Reporter and assignee flows:** `IncidentManagement-flows.postman_collection.json` (same environment) plays complete
stories from both sides. There is no assignee field: the owning team works on an incident, so a team member acts as
the assignee.

| Folder | Story | Who |
|---|---|---|
| A | report → acknowledge → comments both ways → review → resolve → close | Bob reports; Dan (Database) is the assignee, Alice resolves |
| B | escalate to SEV1 and hand over to another team → de-escalate → resolve → close | Dan escalates; Carol (Platform) takes over |
| C | resolve → reopen → resolve again | Erin (Network) |
| D | cancel a duplicate | Dan |

Each flow also checks what each side may not do (403 / 409 / 400), exactly which emails each person gets (Mailpit),
and the history. 51 requests, 94 assertions; it creates new incidents, so it can run again and again.

## Build one service

Each service builds on its own with its Maven wrapper (JDK 21; no Maven installation needed):

```sh
cd incident-service && ./mvnw verify            # tests
docker build -t incident-management/incident-service:local incident-service    # image, from its own folder
```

To run a service from the IDE, start only the infrastructure (`docker compose up -d postgres rabbitmq redis`, plus
`organization-service` for the others); the defaults in each `application.yml` point at `localhost`. Each service's
README lists what it owns, its API, its events and its settings.

## Rules

- **One database per service.** Each service connects with its own user, which cannot connect to the other
  databases (`postgres/init/01-databases.sql`). Each service runs its own Flyway, versions start at `V1`.
- **No shared code at all.** Domain types such as `Actor` or `Severity` and the technical web code (`<service>.web`:
  caller header, error mapping, OpenAPI basics) are copied into each service. There is no parent POM and no shared
  library; a fix to the copied web code is applied in every service.
- **Services talk only through contracts:** organization's REST API and the RabbitMQ events of ADR D14. No service
  uses another one's classes or database.
- **`/internal/**`** endpoints are for service-to-service calls only. Without a gateway they are reachable on
  organization-service's port, so do not publish that port outside a trusted network.
- **Events** go through the outbox of the publishing service, never straight to RabbitMQ, and every consumer skips
  repeats by event id. Each service has its own RabbitMQ user (`rabbitmq/definitions.json`) and declares its own
  exchanges and queues; failed messages end in `<queue>.dlq` after 1 attempt + 3 retries (ADR D8, D14).
