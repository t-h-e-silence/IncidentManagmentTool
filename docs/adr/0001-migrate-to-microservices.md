# ADR-0001: Migrate the modular monolith to event-driven microservices

- **Status:** Proposed
- **Date:** 2026-10-23
- **Deciders:** project owner (pet project, single developer)
- **Sources:** [CircleCI — Monolith to microservices migration strategies](https://circleci.com/blog/monolith-to-microservices-migration-strategies/),
  [GeeksforGeeks — Steps to migrate from monolithic to microservices architecture](https://www.geeksforgeeks.org/system-design/steps-to-migrate-from-monolithic-to-microservices-architecture/)
- **Supersedes (for the new project only):** the "controller-orchestrated monolith" structure described in
  [system-design.md](../system-design.md) and [development-plan.md](../development-plan.md). This repository stays as it is.

---

## 1. Context

### 1.1 What exists today

The Incident Management Tool is a Spring Boot 3.5 / Java 21 modular monolith:

| Module | Owns | Used by |
|---|---|---|
| `organization` | users, teams, memberships, categories → team routing | every request (caller check), routing on create, team checks |
| `incidents` | incidents, comments, lifecycle and severity rules | all incident endpoints |
| `audit` | append-only audit entries | every write action; read by `GET /incidents/{id}/history` |
| `notifications` | email rows, retry policy (1 / 5 / 15 min), SMTP delivery job | create, status changes, escalations |
| `escalations` | escalation records, escalation email texts | severity raise / lowering |

- One `IncidentManagementController` (`@RestController`) calls all five service interfaces **in one
  `@Transactional`**: the incident change, its audit entries (sharing one correlation id), escalation record and
  notification rows commit together or not at all.
- One PostgreSQL database, **one schema per module**, one Flyway with per-module version ranges (1xx … 5xx).
  Cross-module references are UUIDs; there are no cross-schema foreign keys.
- The only module-to-module dependency is `escalations → notifications`.
- Emails are sent after commit by `EmailDeliveryJob`, a scheduled poller (`FOR UPDATE SKIP LOCKED`).
- The caller is identified by the `X-User-Id` header (no real authentication).
- No CI, no metrics or tracing. `docker-compose.yml` runs only Postgres and Mailpit.
- Tests are Mockito unit tests plus Postman collections.

The module boundaries are already clean. Schema per module, UUID references and service interfaces are the
"seams" both articles recommend finding first. That makes this codebase a good candidate for decomposition.

### 1.2 Why migrate (drivers)

1. **Scalability.** We want to scale the parts that carry load independently (see §1.3).
2. **Independent deployment.** Each service is built, containerised and started on its own.
3. **Learning.** The project is a pet project for practising microservice architecture and deployment.

This is forward-looking. There is no production load today, only seed data.

### 1.3 Load analysis: which part will need scaling

Derived from the current controller flows. *T* = active members of the owning team.

| Action | `organization` reads | `incidents` writes | `audit` inserts | Emails queued | Escalation records |
|---|---|---|---|---|---|
| Any request (caller check) | 1 | – | – | – | – |
| `POST /incidents` | +1 routing | 1 | 1 | **T** | – |
| `PATCH` title/description | – | 1 | 1 | 0 | – |
| `PATCH` status (ack / resolve / reopen / cancel) | – | 1 | 1 | 1 … **T + 1** | – |
| `PATCH` severity (escalation with hand-over) | +2–3 team lookups | 1 | 1 | **T(new) + T(old) + 1** | 1 |
| `PATCH` several fields at once | | 1 | up to 3 | sum of the above | ≤ 1 |
| `POST /incidents/{id}/comments` | – | 1 | 1 | 0 | – |
| `GET` lists / incident / history | 1–2 | reads | reads (history only) | – | – |

Conclusions:

- **`notifications` is the hot spot.** It is the only component whose work **multiplies** per request (fan-out of
  *T* to 2*T*+1 emails). Each email is slow, blocking SMTP I/O (timeouts are 10 s) and can be retried up to 3 times.
  It is I/O-bound and bursty: a SEV1 outage creates many incidents, escalations and status changes at once.
  → It needs horizontal scaling (competing consumers) and a queue in front of it. **RabbitMQ is justified here.**
- **`audit` is write-heavy but cheap.** It does exactly 1 small append per changed aspect (1–3 per request) and
  never updates. It is read only by the history endpoint. Load is linear in write requests and an indexed append
  costs little, so **one instance is enough for a long time**. Making it async (a queue consumer) smooths
  bursts and takes its latency off the user's request. It is the classic "non-critical service" both articles
  suggest extracting.
- **`organization` has the most requests but the least work.** Every request of every service asks "who is this
  caller?", and the data changes only through seeding. → Cache it (Redis) instead of scaling it.
- **`incidents` carries the user-facing load.** Writes are 1 per request; reads (lists) grow with data. It scales
  horizontally if it becomes the bottleneck, since it is stateless apart from the database.
- **`escalations` has the least load.** It runs only on severity changes, a small fraction of writes. It exists for
  its **logical boundary**, not for scaling (see decision D2).

---

## 2. Decision drivers / constraints

- **Keep the public HTTP API unchanged:** same paths, methods, request and response bodies, status codes and
  `X-User-Id` header (`docs/openapi.yaml`, Postman collections).
- **Internal APIs:** no specific versioning scheme, but **no breaking changes**. Changes are additive only.
- **Same stack in every service:** Java 21, Spring Boot, Spring Data JPA, Flyway, PostgreSQL.
- **Data boundaries:** a service writes only to the database it owns. It reads other services' data only through
  their API or events.
- **No shared business library.** Independent deployability wins over DRY at the domain level.
- **Local Docker only.** CI/CD, Kubernetes, IaC, service discovery, gateway, observability, real authentication
  and a service mesh are out of scope for now (§6).
- **The monolith is frozen.** The microservices are built in a **new project**; this repository is not modified.

---

## 3. Considered options

| # | Option | Verdict |
|---|---|---|
| O1 | Stay a modular monolith | Rejected: does not meet the scaling and learning drivers |
| O2 | **Strangler fig**: gateway in front of the monolith, extract one service at a time, optionally with a parallel run | Rejected: the articles' default for systems with production users. We have none, and the monolith is frozen, so the routing layer and dual running would cost effort for no benefit |
| O3 | **Big bang into a new project**: rebuild as 5 services, reusing the module code, switch over when complete | **Chosen** |
| O4 | Orchestration: one orchestrator/BFF service keeps the controller's role and calls services over REST | Rejected: recreates a "god service" and puts a synchronous chain in front of every action |
| O5 | **Choreography**: each service owns its use cases and reacts to domain events | **Chosen** |

---

## 4. Decision

We rebuild the system as **five independently deployable Spring Boot services in a new project**. They communicate
**synchronously over REST** for queries that must be answered now. Everything that may happen later is done by
**choreography over RabbitMQ events**.

### Overview

**5 services + 4 infrastructure containers (PostgreSQL with 5 databases, Redis, RabbitMQ, Mailpit) = 9 containers**
locally. Every service runs as 1 instance, except `notification-service`, which runs 1…N. RabbitMQ has 2 exchanges,
4 queues and 4 dead-letter queues. The general architecture diagram is in
[0001-migrate-to-microservices-diagrams.md](0001-migrate-to-microservices-diagrams.md).

### D1. Services and entry points

Each service has its **own REST controller** exposing its slice of the existing public endpoint list, plus
`/internal/**` endpoints for other services. Internal endpoints are not part of the public contract.

| Service | Public endpoints (unchanged paths) | Internal API | Consumes events | Publishes |
|---|---|---|---|---|
| `organization-service` | `GET /teams` | `GET /internal/actors/{idOrUsername}`, `GET /internal/categories/{id}/routing`, `GET /internal/teams/{id}` (with active members, archived flag), `GET /internal/admins`, `GET /internal/users/{id}/recipient` | – | – |
| `incident-service` | `GET /incidents`, `GET /teams/{teamId}/incidents`, `GET /users/{user}/incidents`, `POST /incidents`, `PATCH /incidents/{id}`, `GET /incidents/{id}`, `POST /incidents/{id}/comments` | – | – | `incident.*` events |
| `audit-service` | `GET /incidents/{id}/history` | – | all `incident.*` | – |
| `escalation-service` | – | – | `incident.escalated`, `incident.de-escalated` | `notification.email-requested` |
| `notification-service` | – | – | `incident.created`, `incident.status-changed`, `notification.email-requested` | – |

**Why `incident-service` is not a god service:** it owns only the incident aggregate and its rules. It does not
know who gets emailed, does not build any text, does not write audit entries and does not know its consumers. It
publishes facts ("incident escalated from SEV3 to SEV1, team A → team B, by bob, because …"), and other services
decide what those facts mean for them.

### D2. `escalation-service` is kept, as an event-driven translator rather than a synchronous proxy

Keeping it as an **asynchronous translator** does make sense:

- It owns the escalation records (the escalation history is its data) and the knowledge of *who* must hear about
  an escalation and *what each receiver is told*: the new team, the previous team and the reporter each get their
  own text, nobody gets two emails, and the actor gets none. That is escalation domain knowledge, not email
  delivery.
- `notification-service` stays a generic delivery service. It does not need to understand escalations.
- If a future automatic escalation by team policy is built (system-design §8 Q1, still postponed), the policy has
  a natural home.

*Trade-off accepted:* it is the lightest-loaded service, so it adds a container that rarely works. If that stops
being worth it, its templates can move into `notification-service` without touching `incident-service`.

The decision that **makes** an escalation (severity must go up, optional hand-over, target team must be active)
stays **atomic inside `incident-service`**. `escalation-service` only reacts afterwards.

### D3. Consistency: what is atomic, what is eventual

| Step | Consistency |
|---|---|
| Incident create / details / status / **escalation + team hand-over** / comment, together with its outgoing event | **Atomic.** One local transaction in `incident-service` (incident rows + outbox row) |
| Audit entry | Eventual, may lag by seconds |
| Escalation record | Eventual |
| Emails | Eventual. Urgent ones are fast-tracked (D8) |

Consequences for the unchanged API:

- `GET /incidents/{id}/history` right after a write may not yet show the newest entries.
- `audit-service` answers **404 when it has no entries for the incident**. Every incident gets an
  `INCIDENT_CREATED` entry, so this matches today's behaviour except for a just-created incident during the
  lag. This follows D5 (no cross-service existence check).

### D4. Messaging patterns (RabbitMQ)

1. **Topic exchange `incidents.events`.** Events are `incident.created`, `incident.details-updated`,
   `incident.status-changed`, `incident.escalated`, `incident.de-escalated` and `incident.comment-added`. The
   routing key adds `.urgent` or `.normal` (D8), e.g. `incident.escalated.urgent`.
   **Direct exchange `notifications.commands`** carries `notification.email-requested`.
2. **Transactional outbox** in every publisher (`incident-service`, `escalation-service`). The event is inserted
   into an `outbox` table in the same transaction as the business change. A relay job polls with
   `FOR UPDATE SKIP LOCKED` (the pattern the current email job already uses) and publishes with **publisher
   confirms**. Without the outbox, a crash between commit and publish would lose audit entries and emails.
3. **At-least-once delivery + idempotent consumers.** Every event has an `eventId` (UUID), `occurredAt`,
   `correlationId` and the acting user's id and name. Each consumer records processed `eventId`s in its own
   `processed_events` table, in the same transaction as its effect, and skips duplicates.
4. **Event-carried state.** Events carry what consumers need (before/after severity, status and team, reporter
   id, title, reason), so consumers do not call back into `incident-service`.
5. **Tolerant reader, no shared event JAR.** Each consumer defines its own DTO for the fields it reads and ignores
   unknown fields. New fields may be added. Fields are never removed or renamed (the no-breaking-change rule).
6. **Ordering.** Not guaranteed across incidents. Audit entries are sorted by `occurredAt`, which the producer sets
   in the source transaction.

### D5. Data

- **One PostgreSQL container, one database per service**: `organization`, `incidents`, `audit`,
  `notifications`, `escalations`. Each has its own DB user with rights only on its own database.
- **Each service runs its own Flyway** with only its own migrations. Version numbers restart at `V1` per service;
  the shared 1xx…5xx ranges and `out-of-order` are no longer needed. Seed data per service runs under the `seed`
  profile.
- **A service never writes to a database it does not own.** Cross-service data comes from the owner's API (D6) or
  from events.
- **Cross-service references are not validated** (e.g. `incident.teamId`, `audit.actorId`). The exceptions are
  the business checks that already exist: the routing lookup and "target team is active", which
  `incident-service` asks `organization-service` for.
- **No production data to migrate.** Each new database is created by its Flyway plus seed.

### D6. Organization data: REST + Redis cache-aside

**Options considered for B2:**

| Option | Pros | Cons |
|---|---|---|
| a) Sync REST to `organization-service` on every request | Simple, always fresh | `organization-service` is in every request path, and an outage stops everything |
| **b) REST + Redis cache-aside (chosen)** | Simple; organization data changes only by seeding, so it is almost always a cache hit; Redis is shared infrastructure you wanted to try | Stale up to the TTL; a cold cache plus `organization-service` down still fails |
| c) Event-carried state transfer: each service keeps a local read-only replica of users and teams, fed by `organization.*` events | Fully autonomous, no runtime coupling | Most code (organization would need to publish events it has no write endpoints for today); a replica in each service |
| d) Roles and teams carried in a signed JWT | No lookup per request | Needs real authentication, which is out of scope (G1) |

**Decision:** b). Each service that needs organization data uses an `OrganizationClient` (its own copy, per D7)
that reads Redis first, then calls `/internal/**`, then stores the result **under its own key prefix**
(e.g. `incident-service:actor:{id}`) with a **TTL of 5 minutes**. Only the client that wrote a key reads or
expires it, so the data-ownership rule holds for Redis too. Option c) is the upgrade path if
`organization-service` ever gets write endpoints.

### D7. What is shared, and how

| Thing | Approach | Why |
|---|---|---|
| Domain types (`Severity`, `Actor`, `Recipient`, `TeamRole`, …) | **Copied into each service** that needs them; each service may shape them differently | Breaks coupling (your B4 answer) |
| Event payloads | Each consumer has its own DTO (D4.5) | Same |
| **Technical web concerns**: `@ActingUser` + header parsing, `HttpErrorHandler` and the 4 exception types it maps (400/401/403/404/409), base OpenAPI config | **One small technical library `platform-web`** | Identical, non-business, and it keeps error responses consistent across services as the unchanged API requires |

**Library vs gateway** (your B3 question):

| | Shared technical library (chosen now) | API gateway (future, §6) |
|---|---|---|
| Pros | No extra runtime component; no extra hop; works with "no gateway" (E3) | One host and port for clients, so the public URL truly stays the same; one place for the caller check, CORS, rate limiting and later real authentication; services could trust a resolved header |
| Cons | Every service must upgrade to get a fix; version drift is possible; the caller check is repeated per service | Another component to run and a single point of failure; a temptation to put business logic in it; services still need roles for their own authorization |

They do not exclude each other. When the gateway arrives, it takes over header resolution and the library
shrinks to error mapping.

### D8. Retries, dead letters and urgent notifications

- **Consumer failures** (your C4 question: yes). Spring AMQP stateless retry runs **3 attempts with exponential
  backoff (1 s, 5 s, 25 s)**. After that the message is rejected without requeue and goes through a dead-letter
  exchange to **`<queue>.dlq`**. Dead letters are inspected and re-shovelled by hand in the RabbitMQ management UI
  (`:15672`). The failure is logged at ERROR with `eventId` and `correlationId`.
- **SMTP failures are a different thing and keep today's mechanism.** `notification-service` acknowledges the
  message once the notification row is stored. SMTP retries stay on the existing `RetryPolicy` (1 / 5 / 15 min),
  after which the email is dead-lettered as `FAILED`.
- **Urgent notifications** (your C2 answer). A notification is **urgent when the incident is SEV1 or SEV2** at
  the time of the event (create, escalation, status change).
  - Producers add the routing suffix `.urgent` or `.normal`. `notification-service` binds urgent ones to
    **`notifications.urgent`**, with higher consumer concurrency, separate from **`notifications.normal`**.
  - Urgent emails get their **first SMTP attempt immediately in the consumer**, after the row is stored. Normal
    ones wait for the delivery job. The job processes `priority = URGENT` first.
  - Separate queues were chosen over a RabbitMQ priority queue (`x-max-priority`). A priority queue only reorders
    a backlog, while separate queues let urgent mail have dedicated consumers that a flood of normal mail cannot
    starve.

### D9. Idempotency keys on the public API

`POST /incidents`, `POST /incidents/{id}/comments` and `PATCH /incidents/{id}` accept an **optional**
`Idempotency-Key` header. An optional header is not a breaking change. `incident-service` stores
*(key, caller, request hash, response status, response body, created_at)* for 24 h:

- same key and same request → the stored response is returned;
- same key and a different request → `409`.

Without the header, behaviour is exactly as today. Together with D4.3, this means a repeated request or message
never creates a duplicate incident, audit entry or email.

### D10. Runtime and project layout (local only)

- **New repository** `incident-management-microservices` (this one stays frozen as the reference). It is a
  **Maven multi-module** build: parent POM, `platform-web` library, and one module per service, each with its own
  `Dockerfile`.
- **`docker-compose.yml`** runs `postgres:17` (5 databases created by an init script), `redis:7`,
  `rabbitmq:management`, `mailpit` and the 5 services. The services with public endpoints publish host ports
  **8081** (organization), **8082** (incidents) and **8083** (audit). `notification-service` and
  `escalation-service` have no public API and publish **no host port**, so they can be scaled with
  `--scale`. Services find each other by compose service name.
- Configuration stays in environment variables with defaults in `application.yml`, as today.

### D11. Build order (big bang)

1. **notification-service**: consumer, urgent/normal queues, DLQ, reuses the existing delivery code.
2. **audit-service**: consumer plus `GET /incidents/{id}/history`.
3. **escalation-service**: consumer, records, email texts, outbox to `notifications.commands`.
4. **organization-service**: `GET /teams` plus `/internal/**` and seed data.
5. **incident-service**: public endpoints, outbox, idempotency keys, `OrganizationClient` + Redis.

Steps 1–3 can be developed against hand-published test messages, and step 5 against the running step 4. The
switch-over happens when the whole Postman collection passes against the compose stack. There is no rollback
plan: the frozen monolith remains runnable as it is.

### D12. Testing

- **Mockito unit tests** per service, ported from the monolith's tests along with the code: service
  implementations, controllers, the `OrganizationClient` cache logic and the consumers' duplicate-skipping.
- **Postman smoke tests** against the full `docker-compose` stack. Base URLs are one variable per service
  (`organizationUrl`, `incidentsUrl`, `auditUrl`). Assertions on audit history poll briefly because of D3.
- Integration tests (Testcontainers), contract tests (Pact / Spring Cloud Contract), performance and chaos tests
  are **not** included now (§6).

---

## 5. Consequences

### Positive

- `notification-service` (the real hot spot) and `incident-service` scale horizontally on their own. RabbitMQ
  absorbs bursts, and urgent mail has its own lane.
- A failing audit, escalation or notification service **no longer fails the user's action**. Its events wait in
  the queue.
- Each service builds, deploys and starts independently and has a small codebase.
- `incident-service` stays focused on incidents. Who-gets-told-what lives with escalations and notifications.

### Negative / risks

- **Loss of atomicity.** One action is no longer all-or-nothing across modules: history and emails are eventually
  consistent, and a poison message ends up in a DLQ that has to be handled by hand.
- **"Same public API" holds for paths and payloads, not for the host.** Without a gateway, clients use three base
  URLs (organization, incidents, audit). The Postman collections must switch to per-service variables. The
  gateway (§6) restores a single host.
- **`organization-service` is a synchronous dependency of every service.** Redis reduces this but does not remove
  it: a cold cache while it is down means `503` for the caller check.
- **Stale organization data** for up to 5 minutes. This is acceptable while the data changes only by seeding.
- **More moving parts locally:** 5 services, Postgres, Redis, RabbitMQ and Mailpit. Debugging crosses processes,
  and without tracing (G2) the only link is the `correlationId` in logs.
- **New patterns** (outbox, idempotent consumers, DLQ, idempotency keys) are exactly what the monolith design
  rejected. They are required here and are part of the learning goal.
- **No integration or contract tests.** Outbox relays, RabbitMQ bindings and the cross-service contract are
  verified only by the Postman smoke run, so a broken binding shows up late.
- **Duplicated domain types** can drift. This is intended (B4), but a change to `Severity` must be applied in
  every service by hand.

---

## 6. Out of scope now / follow-up decisions

Each item gets its own ADR when it is picked up.

| Topic | Note |
|---|---|
| API gateway (Spring Cloud Gateway) | Single host for the public API, caller resolution at the edge |
| Real authentication (OIDC/JWT) instead of `X-User-Id` | Enables option d) of D6 |
| Observability | Centralised logs, distributed tracing (Micrometer Tracing + OTel), Actuator/Prometheus metrics, health checks. Both articles call this a prerequisite for production |
| CI/CD, one pipeline per service; IaC; Kubernetes; service discovery | Needed beyond local Docker |
| Integration (Testcontainers), contract and chaos testing | Closes the testing risk in §5 |
| Automatic escalation by team policy | Postponed (system-design §8 Q1). Its rule check would run inside `incident-service` |
| Organization replicas fed by events (D6 option c) | When organization gets write endpoints |
| Service mesh | Not needed at this scale |

---

## 7. Mapping to the source articles

| Article step / pattern | Where it is addressed |
|---|---|
| Assess suitability, define objectives (CircleCI 2.1; GfG step 1) | §1.2, §1.3 |
| Define boundaries with DDD and bounded contexts (CircleCI 2.3; GfG steps 2–3) | D1, D2 (existing modules = bounded contexts) |
| Technology selection (GfG step 4) | §2, D4, D10 |
| Build capabilities and infrastructure first (CircleCI 2.2; GfG step 5) | D10. CI/CD, orchestration and observability are deferred (§6) |
| Strangler fig, parallel run, branch by abstraction (CircleCI 3) | Considered and rejected (O2): big bang into a new project |
| Database decomposition and per-service databases (CircleCI 4.1; GfG step 7) | D5 |
| Sagas and eventual consistency, event-driven updates (CircleCI 4.2) | D3, D4 (choreography, no compensation needed: the only multi-step atomic action stays in one service) |
| Referential integrity across services (CircleCI 4.3) | D5 (not validated), D6 (cached reference data) |
| API gateway (CircleCI roadmap 2; GfG step 8) | Deferred (D7, §6) |
| Fault tolerance and idempotency (GfG step 6) | D8, D9, D4.3 |
| Backward compatibility and versioning (GfG step 8) | §2, D4.5 |
| Testing: unit, contract, production validation (CircleCI 6; GfG step 9) | D12. Contract and chaos testing deferred |
| Deployment and monitoring, IaC (GfG step 10; CircleCI 8) | Local Docker only. The rest is deferred (§6) |
| Incremental rollout (GfG step 11; CircleCI roadmap 1, "non-critical first") | D11 build order |
| Organisation and culture (CircleCI 7; GfG step 12) | Not applicable (single developer) |
