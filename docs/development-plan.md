# Development plan — Incident Management System (monolith, Controller entry point)

**Status (2026-10-02):** all steps done (73 unit tests green). Postponed items are in §7.
**Automatic escalation is postponed** (2026-10-02): only manual escalation is in this plan.
Tick a box only when `mvn clean verify` is green; commit after every step.

## Context
- **One monolith**, no HTTP, no metrics. Functionality is split into modules **organization, incidents, audit, notifications, escalations**.
- Each module has **`model/`**, **`repository/`** and **`service/`** (`XxxService` interface + `XxxServiceImpl`).
- **Modules don't call each other**, with one deliberate exception: **escalations → notifications** (escalation "proxies"
  notifications, choosing the email content by escalation type and receiver).
- A single entry point, **`IncidentManagementController`** (plain Java class), imports the service interfaces and
  orchestrates every user action.
- **PostgreSQL + JPA + Flyway**, one schema per module. **Seed data** for running and trying the application comes from a
  Flyway `seed` profile.
- **Consistency:** every Controller action is **one transaction** (incident change + audit entry + notification rows +
  escalation record commit together or not at all). Emails are sent **after commit** by a scheduled job in
  `notifications` (retries, dead letters), so SMTP problems never fail or corrupt a user action.
- **Testing:** unit tests with **JUnit 5 + Mockito** — every `ServiceImpl` with mocked repositories, the Controller with
  mocked services. No ArchUnit, no integration-test infrastructure.
- Scope: USER reporters (≤ SEV2), lifecycle `OPEN → IN_PROGRESS → RESOLVED` (terminal), team ownership, **severity only
  (no priority)**, manual escalation, reassignment, email over SMTP, append-only audit. Product stories 5, 7, 8, 10.

## Key definitions
**Severity** — how bad the impact is: `SEV1` (critical, total outage) … `SEV4` (minor). It is the only urgency measure:
it orders the team's queue. A USER may report at most `SEV2`.

**Escalation** — the incident needs more urgent and/or different handling. A member of the owning team escalates with a
reason: severity must be **raised**, and the incident may be **handed over to another active team**. It is never
time-based. *(Automatic escalation by a team policy is postponed — see §7.)*

**Reassignment** — moving an incident to another team **without** a severity raise. Not an escalation.

**Escalation emails** — built by the escalation module, per receiver:
| Receiver | Email |
|---|---|
| New owning team (or current team, if not handed over) | "Escalated to you by *name*: *reason*. Severity now SEV1." |
| Previous team (only if handed over) | "Handed over to *team* by *name*: *reason*." |
| Reporter | "Your incident was escalated to SEV1 and is now handled by *team*." |

---

## 1. Package layout
```
org.example
├── Main
├── common/                 shared by all modules, no business logic
│   ├── exception/          UnauthenticatedException, ForbiddenException, NotFoundException, BusinessRuleException
│   └── model/              Actor, Recipient, Severity (SEV1..SEV4), SystemRole, TeamRole
├── controller/
│   └── IncidentManagementController     the only entry point (all user functions, @Transactional)
├── organization/ { model/ repository/ service/ }   users, teams, memberships, categories
├── incidents/    { model/ repository/ service/ }   incident lifecycle, comments
├── audit/        { model/ repository/ service/ }   append-only audit entries
├── notifications/{ model/ repository/ service/ }   notification rows, email templates, sender, delivery job
└── escalations/  { model/ repository/ service/ }   escalation records, escalation emails → notifications
```
**Rules:**
- A module uses only its own packages and `common`. Exception: `escalations.service` uses `NotificationService` and `notifications.model.EmailMessage`.
- Cross-module references are plain `UUID`s (e.g. `Incident.teamId`).
- Only `controller` uses module services, through their interfaces; Spring injects the `ServiceImpl`.
- Services return records (views) from their `model` folder, never JPA entities.

---

## 2. Service methods per module

### 2.1 organization — `OrganizationService`
| Method | Purpose |
|---|---|
| `Actor getActiveActor(UUID userId)` | Who is calling: id, name, system role, team ids. Unknown / deactivated / SYSTEM → `UnauthenticatedException` |
| `List<CategoryView> listActiveCategories()` | Categories a reporter can choose |
| `CategoryRouting getRouting(UUID categoryId)` | Category name + responsible team; inactive/unknown → `NotFoundException` |
| `TeamView getTeam(UUID teamId)` | Team name + active members; unknown → `NotFoundException` |
| `void requireActiveTeam(UUID teamId)` | Exists and not archived, else `BusinessRuleException` |
| `List<Recipient> getActiveMembers(UUID teamId)` | Who to email for a team (id, name, email) |
| `List<Recipient> getActiveAdmins()` | Fallback recipients when a team has no active members |
| `Optional<Recipient> findRecipient(UUID userId)` | A user's email (e.g. the reporter); empty if deactivated |

### 2.2 incidents — `IncidentService`
Changes take the `Actor`, enforce permissions and the lifecycle, and return `IncidentChange(before, after)` so the Controller can audit and notify.
| Method | Rules |
|---|---|
| `IncidentView create(Actor reporter, ReportIncidentCommand cmd, String categoryName, UUID teamId)` | any active user; severity ≤ SEV2; status OPEN. Takes name + team id instead of `CategoryRouting`, because incidents may not import organization's model |
| `IncidentView get(UUID incidentId)` | `NotFoundException` |
| `List<IncidentSummary> listReportedBy(UUID userId)` | newest first |
| `List<IncidentSummary> listTeamQueue(UUID teamId)` | not RESOLVED; by severity (SEV1 first), then oldest |
| `CommentView addComment(Actor actor, UUID incidentId, String text)` | owning team member or reporter; not RESOLVED |
| `IncidentChange acknowledge(Actor actor, UUID incidentId)` | team member; OPEN → IN_PROGRESS, sets `acknowledgedAt` |
| `IncidentChange resolve(Actor actor, UUID incidentId, String note)` | team member; OPEN/IN_PROGRESS → RESOLVED; note required |
| `IncidentChange changeSeverity(Actor actor, UUID incidentId, Severity severity)` | team member; up or down; not RESOLVED; same value → `BusinessRuleException` |
| `IncidentChange escalate(Actor actor, UUID incidentId, Severity newSeverity, UUID targetTeamId)` | team member; severity must be **higher**; `targetTeamId` optional, ≠ current (hand-over makes it `OPEN` again). The reason is required by `EscalateCommand` and kept by audit and escalations |
| `IncidentChange reassign(Actor actor, UUID incidentId, UUID targetTeamId)` | team member or ADMIN; different team; severity unchanged; status back to `OPEN` (the new team has not acknowledged it). The reason is kept in the audit entry and the email |

`Incident` has `@Version`; a concurrent change → `BusinessRuleException("changed concurrently, retry")`.

### 2.3 audit — `AuditService`
| Method | Purpose |
|---|---|
| `void record(AuditRecord record)` | Append one entry: actor, action (`AuditAction`), entity type + id, incident id, details (jsonb), correlation id, occurredAt. No update/delete methods exist |
| `List<AuditEntryView> getIncidentTimeline(UUID incidentId)` | Oldest first |
| `List<AuditEntryView> getActionsByUser(UUID userId)` | Admin view |

### 2.4 notifications — `NotificationService` (+ `EmailSender`, `EmailDeliveryJob`)
| Method | Purpose |
|---|---|
| `void notifyIncident(IncidentNotice notice, List<Recipient> recipients)` | Regular incident emails (created, acknowledged, resolved, reassigned): renders subject/body from its own templates by `NotificationReason`, then `send` |
| `void send(List<EmailMessage> messages)` | Stores one `PENDING` notification per message (recipient, incident id, reason, subject, body) in the caller's transaction. Used by `notifyIncident` and by escalations |
| `List<NotificationView> getForIncident(UUID incidentId)` | Who was told and status |
| `List<NotificationView> getDeadLettered()` | Admin view |
| `NotificationView replay(UUID notificationId)` | DEAD_LETTERED → PENDING, attempts reset |
| `int deliverDue()` | Called by `EmailDeliveryJob` (`@Scheduled`, every 10 s): due PENDING/RETRYING rows (`FOR UPDATE SKIP LOCKED`, batch 50) → `EmailSender` (`JavaMailSender`) → `SENT`, or retry after 1/5/15 min, then `DEAD_LETTERED` + ERROR log |

### 2.5 escalations — `EscalationService` (uses `NotificationService`)
| Method | Purpose |
|---|---|
| `EscalationView recordAndNotify(EscalationRecord record, EscalationRecipients recipients)` | Stores the escalation (actor, reason, old/new severity, old/new team) and sends the receiver-specific emails (table above) through `NotificationService.send`. `EscalationRecipients` = new owning team, previous team (if handed over), reporter |
| `List<EscalationView> getEscalations(UUID incidentId)` | Escalations of an incident |

---

## 3. Controller — `IncidentManagementController`
Every method: `actor = organization.getActiveActor(actorId)`, a new `correlationId`, then the steps below, all in **one `@Transactional`**
(read methods `readOnly`). Private helpers: `audit(...)`, `notifyTeam(...)`, `notifyReporter(...)`, `escalationRecipients(...)`.

| # | Method | Steps (service calls) |
|---|---|---|
| **Organization** |||
| 1 | `List<CategoryView> listCategories(UUID actorId)` | `organization.listActiveCategories` |
| 2 | `TeamView getTeam(UUID actorId, UUID teamId)` | `organization.getTeam` |
| **Reporting** |||
| 3 | `IncidentView reportIncident(UUID actorId, ReportIncidentCommand cmd)` | `organization.getRouting` → `incidents.create` → `audit(INCIDENT_CREATED)` → `notifications.notifyIncident(team members ∨ admins, CREATED)` |
| 4 | `IncidentView getIncident(UUID actorId, UUID incidentId)` | `incidents.get` |
| 5 | `List<IncidentSummary> listMyReportedIncidents(UUID actorId)` | `incidents.listReportedBy` |
| 6 | `CommentView addComment(UUID actorId, UUID incidentId, String text)` | `incidents.addComment` → `audit(COMMENT_ADDED)` |
| **Working an incident** |||
| 7 | `List<IncidentSummary> getTeamQueue(UUID actorId, UUID teamId)` | `organization.getTeam` (exists) → `incidents.listTeamQueue` |
| 8 | `IncidentView acknowledgeIncident(UUID actorId, UUID incidentId)` | `incidents.acknowledge` → `audit(STATUS_CHANGED)` → `notifyReporter(ACKNOWLEDGED)` |
| 9 | `IncidentView resolveIncident(UUID actorId, UUID incidentId, String note)` | `incidents.resolve` → `audit(STATUS_CHANGED)` → `notifyTeam` + `notifyReporter(RESOLVED)` |
| 10 | `IncidentView changeSeverity(UUID actorId, UUID incidentId, Severity severity)` | `incidents.changeSeverity` → `audit(SEVERITY_CHANGED)` |
| 11 | `IncidentView escalateIncident(UUID actorId, UUID incidentId, EscalateCommand cmd)` | if target team: `organization.requireActiveTeam` → `incidents.escalate` → `audit(ESCALATED)` → `escalations.recordAndNotify(escalationRecipients)` |
| 12 | `IncidentView reassignIncident(UUID actorId, UUID incidentId, UUID targetTeamId, String reason)` | `organization.requireActiveTeam` → `incidents.reassign` → `audit(REASSIGNED)` → `notifications.notifyIncident(new team + reporter, REASSIGNED)` |
| **History** |||
| 13 | `List<AuditEntryView> getIncidentTimeline(UUID actorId, UUID incidentId)` | `incidents.get` (exists) → `audit.getIncidentTimeline` |
| 14 | `List<NotificationView> getIncidentNotifications(UUID actorId, UUID incidentId)` | `notifications.getForIncident` |
| 15 | `List<EscalationView> getIncidentEscalations(UUID actorId, UUID incidentId)` | `escalations.getEscalations` |
| **Admin (ADMIN only, else `ForbiddenException`)** |||
| 16 | `List<NotificationView> listDeadLetteredNotifications(UUID adminId)` | `notifications.getDeadLettered` |
| 17 | `NotificationView replayNotification(UUID adminId, UUID notificationId)` | `notifications.replay` → `audit(NOTIFICATION_REPLAYED)` |
| 18 | `List<AuditEntryView> getUserActivity(UUID adminId, UUID userId)` | `audit.getActionsByUser` |


---

## 4. Steps
- [x] **0. Cleanup & skeleton** — `pom.xml`: remove web, validation, actuator, Prometheus, `spring-modulith-*`, Testcontainers, GreenMail, failsafe; keep JPA, Flyway, PostgreSQL, mail, `spring-boot-starter-test` (JUnit 5, Mockito, AssertJ). `application.yml`: drop `management` and the `local` profile; add `spring.main.keep-alive: true` (no web server) and a `seed` profile. Package skeleton (§1), `common` exceptions and value types, `docker-compose.yml` (PostgreSQL + Mailpit).
- [x] **1. organization** — entities `User`, `Team` (+ memberships), `Category`; repositories; `OrganizationServiceImpl` (§2.1); Flyway `V101` schema; seed `R__seed_organization.sql` (admin, SYSTEM user, 5 users, 3 teams, 5 categories); Controller #1–2; unit tests.
- [x] **2. incidents + audit (report & view)** — `Incident`, `Comment`; `IncidentServiceImpl.create/get/listReportedBy`; `AuditServiceImpl` (§2.3); Flyway `V201`, `V301`; Controller #3 (without notify/escalate yet), #4, #5, #13; unit tests.
- [x] **3. notifications** — `Notification` entity, `NotificationServiceImpl` (templates, `send`), `EmailSender`, `EmailDeliveryJob`; Flyway `V401`; wire into #3; Controller #14; unit tests (mocked `JavaMailSender`, retry/dead-letter rules).
- [x] **4. working an incident** — acknowledge/resolve/changeSeverity/addComment/listTeamQueue + permissions + optimistic locking; reassign; Controller #6–10, #12; unit tests.
- [x] **5. escalations (manual)** — `Escalation` (record), `EscalationServiceImpl` with email builder per receiver (§Key definitions) → `NotificationService.send`; Flyway `V501`; `incidents.escalate`; Controller #11, #15; unit tests.
- [x] **6. admin & wrap-up** — Controller #16–18; README (run with `seed` profile, seeded users, Mailpit); update `system-design.md` and `c4-model.md` to this design (Controller orchestration, severity only, manual escalation, escalations → notifications).

### Done notes
- SYSTEM user is created by migration `V101` (needed in every environment); all other users come from the seed.
- Audit is append-only twice over: no update/delete methods in code, and a database trigger rejects `UPDATE`/`DELETE`.
- Escalation emails: owning team first, then previous team, then reporter — each person gets one email, the escalator none.
- SQL and JPA mappings were checked once against PostgreSQL 17 with the `seed` profile (report → emails → acknowledge → comment → severity → reassign/escalate with hand-over → resolve → timeline), outside the repo; there are no integration tests in the project, as agreed.

## 5. Seed data (profile `seed`)
- Location `classpath:db/seed`, added to `spring.flyway.locations` only in the `seed` profile.
- Repeatable scripts (`R__seed_<module>.sql`) with fixed ids and `ON CONFLICT DO NOTHING`, so they run after all migrations and can be re-run safely.
- Content: ADMIN Ada; SYSTEM user; Alice (lead Database, responder Network), Bob (no team, reporter), Carol (lead Platform), Dan (Database), Erin (lead Network); teams Platform, Database, Network; categories Payments, Web application → Platform, Database → Database, VPN, Network → Network.

## 6. Testing (unit tests, JUnit 5 + Mockito)
- **Entities / domain rules:** lifecycle transitions, severity ≤ SEV2 on report, escalation must raise severity, team ≥ 1 member.
- **ServiceImpls** with mocked repositories: e.g. `IncidentServiceImpl.acknowledge` by a non-member → `ForbiddenException`; `EscalationServiceImpl.recordAndNotify` sends the right subject/body to new team, previous team and reporter (captured with `ArgumentCaptor`); `NotificationServiceImpl.deliverDue` retries then dead-letters.
- **Controller** with mocked services: order of calls (`InOrder`), nothing audited/notified when the incident service throws, admin functions reject non-admins.
- **Manual run:** `docker compose up -d`, `mvn spring-boot:run -Dspring-boot.run.profiles=seed`, emails visible in Mailpit (`localhost:8025`).

## 7. Later (not in this plan)
**Automatic escalation by team policy** (severity threshold → hand-over by SYSTEM) · HTTP API / UI over the Controller · integration tests · metrics · OIDC login · monitoring-system (SYSTEM) events · Slack/SMS · admin functions for teams/categories/policies · `TEAM_LEAD`-only actions · user anonymization · confidential incidents.
