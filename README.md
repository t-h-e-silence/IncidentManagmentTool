# Incident Management Tool

A monolith (Java 21, Spring Boot 3, PostgreSQL) where users report incidents, the owning team works and resolves
them, incidents can be escalated or reassigned, the right people are emailed, and every action is audited.

There is **no HTTP API**: every user action is a method on
[`IncidentManagementController`](src/main/java/org/example/controller/IncidentManagementController.java).
Design: [docs/system-design.md](docs/system-design.md) · diagrams: [docs/c4-model.md](docs/c4-model.md) ·
progress: [docs/development-plan.md](docs/development-plan.md).

## Modules
| Package | What it does |
|---|---|
| `controller` | `IncidentManagementController` — the only entry point; one transaction per action |
| `organization` | users, teams with roles, categories → team |
| `incidents` | lifecycle `OPEN → IN_PROGRESS → RESOLVED`, severity, comments, permissions |
| `audit` | append-only audit entries, incident timeline |
| `notifications` | emails stored with the action, sent over SMTP every 10 s, retries and dead letters |
| `escalations` | escalation history and per-receiver escalation emails |
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
mvn spring-boot:run -Dspring-boot.run.profiles=seed    # migrations + seed data, email job running
```
Configuration (environment variables): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`,
`SMTP_PASSWORD`, `MAIL_FROM`.

### Seeded users, teams and categories (`seed` profile)
| User | Id | Role |
|---|---|---|
| Ada Admin | `20000000-0000-0000-0000-000000000001` | ADMIN |
| Alice Lead | `20000000-0000-0000-0000-000000000002` | lead of Database, responder in Network |
| Bob Reporter | `20000000-0000-0000-0000-000000000003` | no team (reports only) |
| Carol Platform | `20000000-0000-0000-0000-000000000004` | lead of Platform |
| Dan Dba | `20000000-0000-0000-0000-000000000005` | responder in Database |
| Erin Network | `20000000-0000-0000-0000-000000000006` | lead of Network |

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
| Organization | `listCategories`, `getTeam` |
| Reporting | `reportIncident`, `getIncident`, `listMyReportedIncidents`, `addComment` |
| Working | `getTeamQueue`, `acknowledgeIncident`, `resolveIncident`, `changeSeverity`, `escalateIncident`, `reassignIncident` |
| History | `getIncidentTimeline`, `getIncidentNotifications`, `getIncidentEscalations` |
| Admin | `listDeadLetteredNotifications`, `replayNotification`, `getUserActivity` |

Errors are exceptions: `UnauthenticatedException` (unknown/inactive user), `ForbiddenException`,
`NotFoundException`, `BusinessRuleException`.
