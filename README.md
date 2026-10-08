# Incident Management Tool

A monolith (Java 21, Spring Boot 3, PostgreSQL) where users report incidents, the owning team works and resolves
them, incidents can be escalated or reassigned, the right people are emailed, and every action is audited.

Every user action is a method on
[`IncidentManagementController`](src/main/java/org/example/controller/IncidentManagementController.java), also exposed
over HTTP by [`IncidentManagementHttpController`](src/main/java/org/example/controller/IncidentManagementHttpController.java)
for testing with Postman.
Design: [docs/system-design.md](docs/system-design.md) · diagrams: [docs/c4-model.md](docs/c4-model.md) ·
progress: [docs/development-plan.md](docs/development-plan.md).

## Modules
| Package | What it does |
|---|---|
| `controller` | `IncidentManagementController` — the only entry point; one transaction per action |
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

## HTTP API (Postman)
Import [`postman/IncidentManagement.postman_collection.json`](postman/IncidentManagement.postman_collection.json):
every endpoint, with the seeded users, teams and categories as collection variables. Run **Report incident** first;
it stores `{{incidentId}}` for the other requests.

[`postman/IncidentManagement-flows.postman_collection.json`](postman/IncidentManagement-flows.postman_collection.json)
holds two end-to-end flows with tests, to run folder by folder in the Collection Runner (or
`newman run postman/IncidentManagement-flows.postman_collection.json`):
1. **Reporter (Bob)** — report, follow, edit and comment on his incident; what a reporter may not do.
2. **Team member (Dan, Database)** — queue, acknowledge, severity, escalate / de-escalate, review, resolve, reopen,
   close, hand-over, reassign, cancel.

The caller is the **`X-User-Id`** header: a username such as `bob`, or a user id (there is no login). Each endpoint calls one controller
method. Errors are RFC 9457 problem details: `401` unknown/inactive user or missing header, `403` not allowed,
`404` not found, `409` business rule (e.g. illegal status change), `400` invalid input.

| Endpoint | Controller method |
|---|---|
| `GET /categories` | `listCategories` |
| `GET /teams` · `GET /teams/{teamId}` | `listTeams` · `getTeam` |
| `GET /teams/{teamId}/queue` · `GET /teams/{teamId}/incidents` | `getTeamQueue` · `listTeamIncidents` |
| `POST /incidents` `{title, description, categoryId, severity}` | `reportIncident` |
| `GET /incidents` · `GET /incidents/mine` · `GET /users/{user}/incidents` (username or id) | `listAllIncidents` · `listMyReportedIncidents` · `listIncidentsReportedBy` |
| `GET /incidents/{id}` | `getIncident` |
| `PATCH /incidents/{id}` `{title?, description?}` | `updateIncidentDetails` |
| `POST /incidents/{id}/comments` `{text}` | `addComment` |
| `POST /incidents/{id}/acknowledge` | `acknowledgeIncident` |
| `POST /incidents/{id}/resolve` `{note}` | `resolveIncident` |
| `PUT /incidents/{id}/status` `{status, note?}` | `changeIncidentStatus` |
| `PUT /incidents/{id}/severity` `{severity}` | `changeSeverity` |
| `POST /incidents/{id}/escalate` · `/de-escalate` `{severity, targetTeamId?, reason}` | `escalateIncident` · `deEscalateIncident` |
| `POST /incidents/{id}/reassign` `{targetTeamId, reason}` | `reassignIncident` |
| `GET /incidents/{id}/timeline` · `/notifications` · `/escalations` | history methods |
| `GET /admin/notifications/dead-lettered` · `POST /admin/notifications/{id}/replay` · `GET /admin/users/{user}/activity` | admin methods |

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

## Using the controller
Inject the controller (e.g. in a test or a `CommandLineRunner`) and call it as a seeded user:
```java
UUID bob = UUID.fromString("20000000-0000-0000-0000-000000000003");
UUID dan = UUID.fromString("20000000-0000-0000-0000-000000000005");
UUID databaseCategory = UUID.fromString("30000000-0000-0000-0000-000000000003");
UUID platform = UUID.fromString("10000000-0000-0000-0000-000000000001");

IncidentView incident = controller.reportIncident(bob,
        new ReportIncidentCommand("DB slow", "timeouts on checkout", databaseCategory, Severity.SEV2));
controller.acknowledgeIncident(dan, incident.id());
controller.escalateIncident(dan, incident.id(), new EscalateCommand(Severity.SEV1, platform, "replica lag growing"));
controller.getIncidentTimeline(bob, incident.id());   // every step with its actor
```
Emails appear in Mailpit at http://localhost:8025 within ~10 seconds.

| Group | Methods |
|---|---|
| Organization | `listCategories`, `listTeams`, `getTeam` |
| Reporting | `reportIncident`, `getIncident`, `listMyReportedIncidents`, `listAllIncidents`, `listIncidentsReportedBy`, `updateIncidentDetails`, `addComment` |
| Working | `getTeamQueue`, `listTeamIncidents`, `acknowledgeIncident`, `resolveIncident`, `changeIncidentStatus`, `changeSeverity`, `escalateIncident`, `deEscalateIncident`, `reassignIncident` |
| History | `getIncidentTimeline`, `getIncidentNotifications`, `getIncidentEscalations` |
| Admin | `listDeadLetteredNotifications`, `replayNotification`, `getUserActivity` |

Errors are exceptions: `UnauthenticatedException` (unknown/inactive user), `ForbiddenException`,
`NotFoundException`, `BusinessRuleException`.
