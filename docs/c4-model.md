# Incident Management System — C4 Model

**Updated:** 2026-10-01
**Notation:** [C4 model](https://c4model.com/) — Level 1 System Context, Level 2 Containers, Level 3 Components, plus one dynamic view.
**Related:** [product design](product%20design/productDesign.md), [system-design.md](system-design.md), [development-plan.md](development-plan.md).

**Arrow colours:** blue = synchronous call, orange = asynchronous event (outbox), grey = user / external interaction.
Colours are mid-tone so they stay readable on both dark (IntelliJ Darcula, GitHub dark) and light backgrounds.

The diagrams show the **planned** system. Elements marked **(later)** are not part of Impl:

| Element | Impl                                          | Later |
|---|-----------------------------------------------|---|
| Notification channels | email over SMTP; stub that logs in `local`/`test` | Slack, SMS |
| Event transport | outbox relay dispatches in-process | no broker planned |
| Authentication | `X-User-Id` header                 | external identity provider (OIDC/JWT) |
| AI investigation assistant | —                                             | separate service with read-only access |

---

## Level 1 — System Context
Who uses the system and which external systems it depends on.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Context
    title System Context — Incident Management System

    Person(reporter, "Reporter", "Any user. Reports an incident by choosing a category.")
    Person(responder, "Responder / Team lead", "Member of a team. Works on, comments on, escalates and resolves the team's incidents.")
    Person(admin, "Admin", "Manages teams, categories and escalation policies (seeded).")

    System(ims, "Incident Management System", "Routes incidents to teams, escalates by reassignment or priority, notifies responders and keeps an immutable audit trail.")

    System_Ext(channels, "Email server", "SMTP relay (corporate relay or a provider such as SES). Slack / SMS later.")
    System_Ext(idp, "Identity provider (later)", "OIDC/JWT login, e.g. Keycloak.")
    System_Ext(monitoring, "Monitoring", "Prometheus + Grafana: metrics such as dead-lettered notifications.")
    System_Ext(ai, "AI investigation assistant (later)", "Reads incidents, timelines and runbooks through read-only APIs / MCP.")

    Rel(reporter, ims, "Reports incidents, views own incidents", "HTTPS/JSON")
    Rel(responder, ims, "Works on, escalates and resolves incidents", "HTTPS/JSON")
    Rel(admin, ims, "Configures teams, categories, policies", "HTTPS/JSON")
    Rel(ims, channels, "Sends notification emails", "SMTP")
    Rel(ims, idp, "Validates tokens", "OIDC")
    Rel(monitoring, ims, "Scrapes metrics", "HTTP /actuator/prometheus")
    Rel(ai, ims, "Reads incidents and timelines", "HTTPS / MCP")
    Rel(channels, responder, "Delivers emails to")

    UpdateRelStyle(reporter, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(responder, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(admin, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(ims, channels, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(ims, idp, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(monitoring, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(ai, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(channels, responder, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

---

## Level 2 — Containers
One deployable application (modular monolith) and one database with one schema per module. There is no message broker: events go through the outbox in-process, and emails leave through SMTP.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Container
    title Containers — Incident Management System

    Person(user, "User", "Reporter, responder or admin")

    System_Boundary(ims, "Incident Management System") {
        Container(app, "Incident Management API", "Java 21, Spring Boot 3, Spring Modulith", "Modular monolith: organization, incidents, escalations, notifications, audit. REST API, outbox relay, email delivery worker.")
        ContainerDb(db, "Database", "PostgreSQL", "One schema per module: organization, incidents, escalations, notifications, audit. Outbox and processed_events tables.")
    }

    System_Ext(channels, "Email server", "SMTP relay")
    System_Ext(idp, "Identity provider (later)", "OIDC")
    System_Ext(monitoring, "Monitoring", "Prometheus + Grafana")
    System_Ext(ai, "AI investigation assistant (later)", "MCP client")

    Rel(user, app, "Uses", "HTTPS/JSON")
    Rel(app, db, "Reads/writes own schemas; incident + outbox in one transaction", "JDBC")
    Rel(app, channels, "Sends notification emails", "SMTP")
    Rel(app, idp, "Validates tokens (later)", "OIDC")
    Rel(monitoring, app, "Scrapes metrics", "HTTP")
    Rel(ai, app, "Reads incidents and audit timeline", "HTTPS / MCP")

    UpdateRelStyle(user, app, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(app, db, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(app, channels, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(app, idp, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(monitoring, app, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(ai, app, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

---

## Level 3 — Components of the Incident Management API
Each module is a component with a public `api` package; everything else is internal.
Arrows between modules are either **synchronous** calls to another module's `api` or **asynchronous** events from the publisher's outbox.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Component
    title Components — Incident Management API

    Person(user, "User", "Reporter, responder or admin")

    Container_Boundary(app, "Incident Management API") {
        Component(org, "organization", "Spring module", "Users, teams, memberships with per-team role, category → team routing. OrganizationApi.")
        Component(inc, "incidents", "Spring module", "Incident lifecycle, severity, priority, owning team, comments, escalate(). Only writer of team_id and priority. IncidentApi.")
        Component(esc, "escalations", "Spring module", "Escalation policies, automatic escalation decisions (once per incident + policy). EscalationApi.")
        Component(notif, "notifications", "Spring module", "One notification per recipient; delivery worker sends due ones, retry with backoff, dead letter, replay. NotificationApi.")
        Component(audit, "audit", "Spring module", "Append-only audit entries, incident timeline. AuditApi (read-only).")
        Component(relay, "Outbox relay", "Scheduled poller", "Reads PENDING outbox rows (FOR UPDATE SKIP LOCKED) and dispatches events; retries and dead-letters.")
        Component(channel, "Channel adapter", "NotificationChannel", "EmailChannel (JavaMailSender); LogChannel stub in local/test.")
    }

    ContainerDb(db, "Database", "PostgreSQL", "Schemas: organization, incidents, escalations, notifications, audit")
    System_Ext(channels, "Email server", "SMTP relay")

    Rel(user, inc, "Report, change status/severity/priority, escalate, comment, resolve", "REST")
    Rel(user, org, "Configure teams and categories (admin)", "REST")
    Rel(user, audit, "View incident timeline", "REST")

    Rel(inc, org, "teamForCategory, roleOf, team exists", "sync")
    Rel(esc, org, "Target team valid", "sync")
    Rel(esc, inc, "escalate() as SYSTEM, idempotent by decisionId", "sync, from outbox step")
    Rel(notif, org, "membersOf, userById", "sync")

    Rel(inc, relay, "Incident events via incidents.outbox", "same transaction")
    Rel(relay, esc, "IncidentCreated, SeverityChanged, StatusChanged", "event")
    Rel(relay, notif, "IncidentCreated, StatusChanged, IncidentEscalated", "event")
    Rel(relay, audit, "All incident, escalation and config events", "event")

    Rel(notif, channel, "Deliver")
    Rel(channel, channels, "Send email", "SMTP")
    Rel(inc, db, "incidents schema", "JDBC")
    Rel(org, db, "organization schema", "JDBC")

    UpdateRelStyle(user, inc, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(user, org, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(user, audit, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(inc, org, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(esc, org, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(esc, inc, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(notif, org, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(inc, relay, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(relay, esc, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(relay, notif, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(relay, audit, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(notif, channel, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(channel, channels, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(inc, db, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(org, db, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateLayoutConfig($c4ShapeInRow="4", $c4BoundaryInRow="1")
```

Not drawn to keep the diagram readable: `escalations`, `notifications` and `audit` also read/write their own schemas, and every consumer records `processed_events` for idempotency.

---

## Dynamic view — automatic escalation
A responder raises severity SEV2 → SEV1; the team's policy (threshold SEV1, target team B) escalates the incident.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Dynamic
    title Dynamic — severity raised, incident escalated to team B

    Person(responder, "Responder", "Member of team A")
    Component(inc, "incidents", "Spring module")
    Component(relay, "Outbox relay", "Scheduled poller")
    Component(esc, "escalations", "Spring module")
    Component(org, "organization", "Spring module")
    Component(notif, "notifications", "Spring module")
    Component(audit, "audit", "Spring module")

    Rel(responder, inc, "changeSeverity(SEV1); tx: incident + outbox IncidentSeverityChanged", "REST")
    Rel(relay, esc, "IncidentSeverityChanged; policy.evaluate records EscalationDecision (UNIQUE incident+policy)", "event")
    Rel(esc, inc, "escalate(team B, P1, SYSTEM, decisionId); tx: incident + outbox IncidentEscalated", "sync")
    Rel(inc, org, "team B exists and is not archived", "sync")
    Rel(relay, notif, "IncidentEscalated", "event")
    Rel(notif, org, "membersOf(team B)", "sync")
    Rel(relay, audit, "IncidentSeverityChanged, IncidentEscalated", "event")

    UpdateRelStyle(responder, inc, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(relay, esc, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(esc, inc, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(inc, org, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(relay, notif, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(notif, org, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(relay, audit, $textColor="#E07020", $lineColor="#E07020")
```

Result: team B owns the incident with priority P1, its members are notified, the status is unchanged, and the
audit timeline shows the severity change and the escalation under one `correlationId`.
