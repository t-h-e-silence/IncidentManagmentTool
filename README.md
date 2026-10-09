# Incident Management Tool

A monolith (Java 21, Spring Boot 3, PostgreSQL) where users report incidents, the owning team works and resolves
them, incidents can be escalated or reassigned, the right people are emailed, and every action is audited.

The single entry point is the REST controller
[`IncidentManagementController`](src/main/java/modules/controller/IncidentManagementController.java): every endpoint
is one user action.
Design: [docs/system-design.md](docs/system-design.md) · diagrams: [docs/c4-model.md](docs/c4-model.md) ·
progress: [docs/development-plan.md](docs/development-plan.md).

## Modules
| Package | What it does |
|---|---|
| `controller` | `IncidentManagementController` — the only (REST) controller; one transaction per request |
| `organization` | users, teams with roles, categories → team |
| `incidents` | lifecycle `OPEN → IN_PROGRESS → IN_REVIEW → RESOLVED → CLOSED` (or `CANCELLED`), severity, comments, permissions |
| `audit` | append-only audit entries, incident timeline |
| `notifications` | emails stored with the action, sent over SMTP every 10 s, retries and dead letters |
| `escalations` | escalation / de-escalation history and per-receiver emails |
| `common` | exceptions and shared value types (`Actor`, `Recipient`, `Severity`, …) |

Each module has `model/`, `repository/` and `service/` (`XxxService` + `XxxServiceImpl`).

## Requirements
- Java 21, Maven 3.9+
- Docker (for PostgreSQL and Mailpit), or your own PostgreSQL 17 and SMTP server

## Build and test
```bash
mvn clean verify        # unit tests (JUnit 5 + Mockito)
```

## Run with demo data
```bash
docker compose up -d                                   # PostgreSQL on 5432, Mailpit on 1025 / http://localhost:8025
mvn spring-boot:run -Dspring-boot.run.profiles=seed    # migrations + seed data, email job, HTTP on :8080
```
Configuration (environment variables): `PORT` (default 8080), `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SMTP_HOST`,
`SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `MAIL_FROM`.

## HTTP API
The caller is the **`X-User-Id`** header: a username such as `bob`, or a user id (there is no login).
Errors are RFC 9457 problem details: `401` unknown/inactive user or missing header, `403` not allowed,
`404` not found, `409` business rule (e.g. illegal status change), `400` invalid input.

| # | Endpoint | What it does |
|---|---|---|
| 1 | `GET /teams` | list teams with their active members |
| 2 | `GET /incidents` | list all incidents |
|   | `GET /teams/{teamId}/incidents` | list a team's incidents |
|   | `GET /users/{user}/incidents` | list incidents reported by a user (username or id) |
| 3 | `POST /incidents` `{title, description?, categoryId, severity}` | create an incident (reporters ≤ SEV2); the category's team is emailed |
| 4 | `PATCH /incidents/{id}` `{title?, description?, severity?, targetTeamId?, status?, reason?}` | update an incident, see below |
|   | `GET /incidents/{id}` | view an incident with its comments |
|   | `POST /incidents/{id}/comments` `{text}` | comment (owning team or reporter) |
|   | `GET /incidents/{id}/history` | audit history: who did what, when, with from/to and reasons |

**`PATCH /incidents/{id}`** — fields left out stay unchanged; the changes are applied in this order, all or nothing:
- `title`, `description` — owning team or reporter; no emails.
- `severity` + `reason` — owning team. **Higher = escalation**, optionally handing the incident over to another
  active team with `targetTeamId`; **lower = de-escalation**, the team keeps it. Both are recorded and emailed to the
  owning team, the previous team (on hand-over) and the reporter.
- `status` (+ `reason` to resolve, cancel or reopen) — owning team:
  `OPEN → IN_PROGRESS → IN_REVIEW → RESOLVED → CLOSED`, `IN_REVIEW → IN_PROGRESS`, `RESOLVED → IN_PROGRESS` (reopen),
  `OPEN | IN_PROGRESS | IN_REVIEW → CANCELLED`. Emails: acknowledged (`OPEN → IN_PROGRESS`) → reporter; resolved,
  reopened, cancelled → team and reporter.

### Swagger / OpenAPI
With the app running: **Swagger UI** at http://localhost:8080/swagger-ui.html (click **Authorize**, enter a username
such as `dan`, then *Try it out*), the spec at http://localhost:8080/v3/api-docs (`.yaml` for YAML). Both are
generated from the controller (springdoc); descriptions, examples and error responses are in
`IncidentManagementController` and `OpenApiConfig`.

A copy of the spec is in [`docs/openapi.yaml`](docs/openapi.yaml) (also importable into Postman). After changing an
endpoint, refresh it from the running app: `curl -s localhost:8080/v3/api-docs.yaml > docs/openapi.yaml`.

### Postman
- [`postman/IncidentManagement.postman_collection.json`](postman/IncidentManagement.postman_collection.json) — every
  endpoint and PATCH variant, with the seeded users, teams and categories as variables. Run **Create incident**
  first; it stores `{{incidentId}}`.
- [`postman/IncidentManagement-flows.postman_collection.json`](postman/IncidentManagement-flows.postman_collection.json)
  — two end-to-end flows with tests, run folder by folder in the Collection Runner (or `newman run …`):
  1. **Reporter (bob)** — create, list, view, rename, comment, history; what a reporter may not do.
  2. **Team member (dan, Database)** — acknowledge, escalate / de-escalate, review, resolve, reopen, close,
     hand-over to Platform, cancel, a combined PATCH.
- [`postman/IncidentManagement-simple-flows.postman_collection.json`](postman/IncidentManagement-simple-flows.postman_collection.json)
  — the same two roles as short happy paths with no error cases; email steps check Mailpit (`{{mailpitUrl}}`) after
  waiting 12 s:
  1. **Reporter (bob)** — create, view, edit, comment, my incidents, the "being worked on" email, history.
  2. **Resolver (dan, Database)** — the "new incident" email, take it, comment, edit, escalate, review, resolve
     (Bob gets the "resolved" email), close, history.

### Seeded users, teams and categories (`seed` profile)
| User | Username | Id | Role |
|---|---|---|---|
| Ada Admin | `ada` | `20000000-0000-0000-0000-000000000001` | ADMIN |
| Alice Lead | `alice` | `20000000-0000-0000-0000-000000000002` | lead of Database, responder in Network |
| Bob Reporter | `bob` | `20000000-0000-0000-0000-000000000003` | no team (reports only) |
| Carol Platform | `carol` | `20000000-0000-0000-0000-000000000004` | lead of Platform |
| Dan Dba | `dan` | `20000000-0000-0000-0000-000000000005` | responder in Database |
| Erin Network | `erin` | `20000000-0000-0000-0000-000000000006` | lead of Network |

Over HTTP, use the username in `X-User-Id` (e.g. `X-User-Id: bob`); the id works too.

Teams: Platform `10000000-…-0001`, Database `10000000-…-0002`, Network `10000000-…-0003`.
Categories: Payments `30000000-…-0001` and Web application `…-0002` → Platform; Database `…-0003` → Database;
VPN `…-0004` and Network `…-0005` → Network.

Emails appear in Mailpit at http://localhost:8025 within ~10 seconds.
