# organization-service

Users, teams, memberships and category routing of the incident management system. An independent project: it
shares no code with the other services, which reach it only through its HTTP API. Architecture: ADR-0001 (D1, D6) in
the system's `docs/adr/`.

## Owns

| What | Where |
|---|---|
| Database `organization` (schema `organization`) | Flyway, `src/main/resources/db/migration`; `V3__demo_data.sql` adds the demo users, teams and categories |

## API

| Endpoint | For | Notes |
|---|---|---|
| `GET /teams` | users | caller in `X-User-Id` |
| `GET /internal/actors/{user}` | other services | who may act; 401 if unknown, deactivated or SYSTEM |
| `GET /internal/users/{user}/id` | other services | id of a user given by username or id, active or not |
| `GET /internal/categories/{id}/routing` | other services | team of an active category |
| `GET /internal/teams/{id}` | other services | team with archived flag and active members |
| `GET /internal/admins` | other services | active admins |
| `GET /internal/users/{id}/recipient` | other services | name and email of a user who may be emailed |

Full description: `openapi.yaml`, or Swagger UI at http://localhost:8081/swagger-ui.html. `/internal/**` is not part
of the public API; callers cache its answers (ADR D6) and rely on the fields only growing, never changing.

Events: none published, none consumed.

## Settings

| Variable | Default |
|---|---|
| `PORT` | `8081` |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5434/organization`, `organization`, `organization` |

## Build and run

```sh
./mvnw verify                       # tests (JDK 21)
./mvnw spring-boot:run              # needs its Postgres database
docker build -t incident-management/organization-service:local .
```
