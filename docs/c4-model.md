# Incident Management System — C4 Model

**Updated:** 2026-10-02
**Notation:** [C4 model](https://c4model.com/) — Level 1 System Context, Level 2 Containers, Level 3 Components, plus one dynamic view.
**Related:** [product design](product%20design/productDesign.md), [system-design.md](system-design.md), [development-plan.md](development-plan.md).

**Arrow colours:** blue = synchronous call inside the application, orange = after commit (email delivery), grey = user / external interaction.
Colours are mid-tone so they stay readable on both dark (IntelliJ Darcula, GitHub dark) and light backgrounds.

The diagrams show the **implemented** system. Elements marked **(later)** are not built:

| Element | Now | Later |
|---|---|---|
| User access | Java methods on `IncidentManagementController` (no HTTP); caller passes the acting user id | HTTP API / UI, login (OIDC) |
| Notification channel | email over SMTP (Mailpit locally) | Slack, SMS |
| Escalation | manual, by a team member | automatic by team policy |
| AI investigation assistant | — | separate service with read-only access |

---

## Level 1 — System Context
Who uses the system and which external systems it depends on.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Context
    title System Context — Incident Management System

    Person(reporter, "Reporter", "Any user. Reports an incident by choosing a category (severity up to SEV2).")
    Person(responder, "Responder / Team lead", "Member of a team. Acknowledges, comments on, escalates, reassigns and resolves the team's incidents.")
    Person(admin, "Admin", "Reassigns any incident, replays failed emails, reviews user activity.")

    System(ims, "Incident Management System", "Routes incidents to teams, escalates by raising severity and handing over, emails the right people and keeps an append-only audit trail.")

    System_Ext(smtp, "Email server", "SMTP relay (corporate relay or a provider; Mailpit locally).")
    System_Ext(ai, "AI investigation assistant (later)", "Reads incidents and timelines through read-only functions.")

    Rel(reporter, ims, "Reports incidents, follows them", "Controller methods")
    Rel(responder, ims, "Works on, escalates and resolves incidents", "Controller methods")
    Rel(admin, ims, "Reassigns; replays failed emails", "Controller methods")
    Rel(ims, smtp, "Sends notification emails", "SMTP")
    Rel(ai, ims, "Reads incidents and timelines")
    Rel(smtp, responder, "Delivers emails to")

    UpdateRelStyle(reporter, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(responder, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(admin, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(ims, smtp, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(ai, ims, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(smtp, responder, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

---

## Level 2 — Containers
One deployable application (monolith, no web server) and one PostgreSQL database with one schema per module.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Container
    title Containers — Incident Management System

    Person(user, "User", "Reporter, responder or admin")

    System_Boundary(ims, "Incident Management System") {
        Container(app, "Incident Management application", "Java 21, Spring Boot 3", "Monolith: organization, incidents, audit, notifications, escalations behind IncidentManagementController. Scheduled email delivery job.")
        ContainerDb(db, "Database", "PostgreSQL 17", "One schema per module: organization, incidents, audit, notifications, escalations. Flyway migrations; demo data with the seed profile.")
    }

    System_Ext(smtp, "Email server", "SMTP relay")

    Rel(user, app, "Calls user functions", "Java")
    Rel(app, db, "One transaction per action: incident + audit + notification rows (+ escalation)", "JDBC")
    Rel(app, smtp, "Sends pending emails after commit", "SMTP")

    UpdateRelStyle(user, app, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(app, db, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(app, smtp, $textColor="#E07020", $lineColor="#E07020")
    UpdateLayoutConfig($c4ShapeInRow="3", $c4BoundaryInRow="1")
```

---

## Level 3 — Components of the application
Each module is a component with `model/`, `repository/` and `service/` (interface + Impl). Modules don't call each
other; the Controller orchestrates them. The only module-to-module call is escalations → notifications.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Component
    title Components — Incident Management application

    Person(user, "User", "Reporter, responder or admin")

    Container_Boundary(app, "Incident Management application") {
        Component(controller, "IncidentManagementController", "Spring component", "The only entry point. Checks the actor, calls the services in order, one transaction per action.")
        Component(org, "organization", "OrganizationService", "Users, teams with per-team roles, category → team routing, recipients.")
        Component(inc, "incidents", "IncidentService", "Lifecycle, severity, owning team, comments, permission checks.")
        Component(audit, "audit", "AuditService", "Append-only audit entries; incident timeline; user activity.")
        Component(esc, "escalations", "EscalationService", "Escalation history; one email per receiver (owning team, previous team, reporter).")
        Component(notif, "notifications", "NotificationService", "Email templates, one row per recipient, retries, dead letters, replay.")
        Component(job, "EmailDeliveryJob", "Scheduled, every 10 s", "Sends due notifications (FOR UPDATE SKIP LOCKED); retries after 1/5/15 min.")
    }

    ContainerDb(db, "Database", "PostgreSQL", "Schemas: organization, incidents, audit, notifications, escalations")
    System_Ext(smtp, "Email server", "SMTP")

    Rel(user, controller, "Report, acknowledge, comment, escalate, reassign, resolve, timeline", "Java")
    Rel(controller, org, "getActiveActor, getRouting, members, admins, recipients")
    Rel(controller, inc, "create, acknowledge, resolve, changeSeverity, escalate, reassign, addComment")
    Rel(controller, audit, "record, getIncidentTimeline")
    Rel(controller, notif, "notifyIncident, getForIncident, replay")
    Rel(controller, esc, "recordAndNotify, getEscalations")
    Rel(esc, notif, "send(escalation emails)")
    Rel(job, notif, "deliverDue")
    Rel(job, smtp, "Send email", "SMTP")
    Rel(inc, db, "incidents schema", "JDBC")
    Rel(notif, db, "notifications schema", "JDBC")

    UpdateRelStyle(user, controller, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(controller, org, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(controller, inc, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(controller, audit, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(controller, notif, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(controller, esc, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(esc, notif, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(job, notif, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(job, smtp, $textColor="#E07020", $lineColor="#E07020")
    UpdateRelStyle(inc, db, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(notif, db, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateLayoutConfig($c4ShapeInRow="4", $c4BoundaryInRow="1")
```

Not drawn to keep the diagram readable: organization, audit and escalations also read/write their own schemas.

---

## Dynamic view — manual escalation with hand-over
Dan (Database) escalates a SEV3 incident to SEV1 and hands it over to Platform.

```mermaid
%%{init: {"themeVariables": {"textColor": "#8A8F98"}}}%%
C4Dynamic
    title Dynamic — incident escalated from Database to Platform

    Person(dan, "Dan", "Responder in Database")
    Component(controller, "IncidentManagementController", "Spring component")
    Component(org, "organization", "OrganizationService")
    Component(inc, "incidents", "IncidentService")
    Component(audit, "audit", "AuditService")
    Component(esc, "escalations", "EscalationService")
    Component(notif, "notifications", "NotificationService")
    Component(job, "EmailDeliveryJob", "Scheduled")

    Rel(dan, controller, "escalateIncident(SEV1, Platform, reason)", "Java")
    Rel(controller, org, "getActiveActor; requireActiveTeam(Platform)")
    Rel(controller, inc, "escalate: SEV3 → SEV1, team → Platform, status → OPEN")
    Rel(controller, audit, "record(ESCALATED, from/to, reason)")
    Rel(controller, esc, "recordAndNotify(record, Platform members, Database members, reporter)")
    Rel(esc, notif, "send: 'Escalated to you' / 'Handed over to Platform' / 'Your incident was escalated'")
    Rel(job, notif, "after commit: deliverDue → SMTP")

    UpdateRelStyle(dan, controller, $textColor="#8A8F98", $lineColor="#8A8F98")
    UpdateRelStyle(controller, org, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(controller, inc, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(controller, audit, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(controller, esc, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(esc, notif, $textColor="#4A90E2", $lineColor="#4A90E2")
    UpdateRelStyle(job, notif, $textColor="#E07020", $lineColor="#E07020")
```

Result: Platform owns the incident at SEV1 and status `OPEN`; Platform members, the remaining Database members and the
reporter each get their own email; the timeline shows the escalation with Dan as the actor and the reason.
