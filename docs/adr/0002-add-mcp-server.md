# ADR-0002: Add an MCP server so AI assistants can do incident work

- **Status:** Proposed
- **Date:** 2026-11-20
- **Deciders:** project owner (pet project, single developer)
- **Diagrams:** [0002-add-mcp-server-diagrams.md](0002-add-mcp-server-diagrams.md)
- **Related:** [ADR-0001](0001-migrate-to-microservices.md): D1 (services and entry points), D7 (nothing shared),
  D9 (idempotency keys), D13 (no gateway), §6 (real authentication is a follow-up)
- **Sources:** [Model Context Protocol specification](https://modelcontextprotocol.io/specification),
  [Spring AI MCP server reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)

---

## 1. Context

### 1.1 What exists today

The system is five independent Spring Boot services (ADR-0001). Three have a public HTTP API, called directly on
their own ports with the caller in `X-User-Id`:

| Service | Public endpoints |
|---|---|
| organization-service :8081 | `GET /teams`: all teams (archived too) with active members (`userId`, `name`, `email`, team `role`) |
| incident-service :8082 | `GET /incidents`, `GET /teams/{id}/incidents`, `GET /users/{user}/incidents` (reported by), `GET /incidents/{id}`, `POST /incidents`, `PATCH /incidents/{id}` (title, description, severity + hand-over, status, reason), `POST /incidents/{id}/comments` |
| audit-service :8083 | `GET /incidents/{id}/history` |

What the API does **not** offer (checked 2026-11-19):

- No query parameters or paging on the lists: no filter by status, severity or date.
- No public list of **categories** (needed to report an incident) and no public "who am I": user ids, usernames and
  the system role (`ADMIN` / `USER`) are only on `/internal/**`.
- Escalation records (escalation-service) and email delivery state (notification-service) have no HTTP API.

How permissions work today:

- **Reads are open** to every active user: any incident, any history.
- **Writes are checked by incident-service:** title, description and comments are for the owning team or the
  reporter; severity and status are for the owning team only. A reporter may set at most SEV2.
- `ADMIN` gives **no extra rights** to change incidents, and `TEAM_LEAD` and `RESPONDER` have the same rights.
- There is no real authentication: `X-User-Id` is trusted (ADR-0001 §6).

### 1.2 Why (drivers)

1. **Do incident work in natural language** from an AI assistant (Claude Code, Claude Desktop, an IDE), e.g.
   *"create an incident: checkout takes 10 s since this morning, many customers affected"*, *"acknowledge it and
   escalate to SEV1, the database is down"*.
2. **Status checks and analytics** without writing curl or SQL, e.g. *"what is open for my team, urgent first?"*,
   *"what happened to incident X?"*, *"mean time to resolve last week?"*
3. **What the assistant can do depends on who uses it.** A simple reporter must not get admin tools.
4. **Learning:** practise MCP as one more client of a microservice system.

## 2. Decision drivers / constraints

- **ADR-0001 rules stay:** no shared code (D7), services talk only through contracts, and `/internal/**` is for
  service-to-service calls only.
- **The services remain the only source of truth** for business rules and permissions. Nothing may bypass them.
- **Same stack as the services:** Java 21, Spring Boot 3.5.x, Maven wrapper, one independent project.
- **Local first:** it runs next to the Docker Compose stack; no hosting, no real authentication yet.
- **Small and replaceable:** removing the MCP server must not affect any service.

## 3. Considered options

| # | Option | Verdict |
|---|---|---|
| O1 | **Separate project `mcp-server/`, a client of the public REST APIs** | **Chosen**: respects all ADR-0001 rules; a pure adapter that can be removed at no cost |
| O2 | MCP endpoint inside incident-service | Rejected: mixes an AI adapter into a domain service, gives it only one service's data (teams and history are elsewhere), and couples its deployment to the MCP library |
| O3 | MCP server reading the services' databases directly | Rejected: breaks database-per-service (ADR-0001 D5) and bypasses every permission check |
| O4 | MCP server calling `/internal/**` for what the public API lacks | Rejected: internal endpoints are not a public contract. The gaps are filled with new public endpoints instead (D9) |
| O5 | No MCP server: the assistant calls the APIs itself (curl / OpenAPI tools) | Rejected: no role-based tool set, raw UUIDs and errors, no combined reads or analytics; the model has to learn three APIs |

## 4. Decision

Build **`mcp-server/`**, an independent project that exposes the system to AI assistants through the Model
Context Protocol. It is an **adapter (a backend-for-frontend for an assistant)**, not a domain service: it owns no
data and makes no business decisions.

```
MCP client ──stdio──▶ mcp-server ──HTTP, X-User-Id: <configured user>──▶ incident-service     :8082
 (Claude Code,          │                                               ├▶ organization-service :8081 (public only)
  Desktop, IDE)         │ resolves identity at start, registers the     └▶ audit-service        :8083
                        │ tools of that user's role
```

### D1. Place and boundaries

- A new top-level folder `mcp-server/`, structured like a service: its own `pom.xml`, `mvnw`, `src/`, `README`.
  It is **not** under `services/`, because it is a client of the system, not part of it.
- It uses **only public endpoints** of organization-, incident- and audit-service. No `/internal/**`, no
  database, no RabbitMQ, no Redis, no copied business logic. Small types (`Severity`, `IncidentStatus`) are copied
  as plain enums for tool schemas (ADR-0001 D7).
- The services do not know it exists. Every change it makes is an ordinary API call, so events, outbox, emails and
  audit history all happen as for any other client.

### D2. Responsibilities

| The MCP server is responsible for | It is not responsible for (stays in the services) |
|---|---|
| Exposing capabilities as MCP **tools, resources and prompts** with clear names, descriptions and schemas | Business rules: lifecycle transitions, the SEV2 limit for reporters, routing, escalation |
| **Identity:** acting as one configured user and showing only the tools of that user's role (D4, D5) | **Enforcing** permissions: every request is still checked by the service |
| **Model-friendly input:** usernames instead of UUIDs, enums, explaining required reasons | Data, state and caches |
| **Model-friendly output:** names instead of ids, trimmed fields, incident + comments + history as one timeline | Side effects: events, outbox, emails, audit records |
| **Combined reads** across services, and **analytics** computed from them (D7) | Authentication (a follow-up, §6) |
| **Error translation:** a problem detail becomes a clear tool error the model can act on | |
| **Guardrails:** idempotency keys on writes, read-only / destructive hints on tools (D8) | |

**Rule of thumb:** if every client (a web UI, Postman, the MCP server) would need a behaviour, it belongs in a
service. If it exists only to make an assistant effective, it belongs in the MCP server.

### D4. Identity: who the assistant acts as

- Each server process acts as **one user**, `INCIDENT_MCP_USER` (username or id), sent as `X-User-Id` on every
  request. To act as someone else, start another process with another value.
- At start-up the server **resolves the user once**: id, name, system role, teams and team roles. If the user is
  unknown or inactive, it refuses to start, with a clear message.
- This is **not security.** `X-User-Id` is not authenticated, so anyone can configure `ada`. Choosing tools by
  role keeps each user's tool set small and correct; the services still decide what is allowed. Real protection
  comes with real authentication (§6). An HTTP transport with OAuth, as the MCP specification describes, would
  then pass the user's token instead of a configured name.
- **Not chosen:** an `actAs` parameter on every tool. It would let the model act as anyone, admin included.

### D5. Tools depend on the user's role

The server registers only the tools of the resolved role. The model never sees tools its user cannot use.

| Role (how it is derived) | Demo users | Incident work | Status checks | Analytics |
|---|---|---|---|---|
| **Reporter**: `USER` in no team | bob | `report_incident`; `edit_incident` and `add_comment` on own incidents | `my_incidents`, `incident_status`, `incident_timeline` (own) | own incidents only: open / closed, mean time to resolve |
| **Responder**: `USER` in ≥ 1 team (`TEAM_LEAD` and `RESPONDER` have the same rights) | alice, carol, dan, erin | All of the above, plus on their teams' incidents: `acknowledge`, `send_to_review`, `back_to_work`, `resolve(note)`, `close`, `reopen(reason)`, `cancel(reason)`, `escalate(severity, reason, handOverTo?)`, `de_escalate(severity, reason)` | `team_queue` (active, urgent first), `stale_incidents(hours)` | their teams: backlog by status and severity, MTTA, MTTR, escalations and hand-overs, reopen rate, ageing |
| **Admin**: system role `ADMIN` | ada | None: incident-service lets only team members change incidents. An admin who is also in a team gets the responder tools too | any incident, timeline and history; `system_status` (each service reachable) | global: all teams compared, trends, weekly report |

All roles get `list_teams`, `team_members(team)` and `my_teams`.

Lifecycle tools are **named steps** rather than one generic "update status": each maps to one allowed transition
of the state machine (OPEN → IN_PROGRESS → IN_REVIEW → RESOLVED → CLOSED, reopen, cancel), so the model makes fewer
invalid moves. They all send `PATCH /incidents/{id}`; the service still checks every transition.

### D6. Resources and prompts

- **Resources:**
  - `incident://{id}` (an incident with comments)
  - `teams://roster`
  - the lifecycle and permission rules
  - the category list
  - this ADR and ADR-0001
- **Prompts:**
  - *triage*: turn a free-text description into title, category, severity and description, and ask when unsure
  - *post-mortem* draft from a timeline
  - *shift handover* / *daily summary* for a team
  - *weekly report* (admin)

### D7. Analytics and filters are computed in the MCP server, for now

- Lists are fetched in full and filtered by status, severity and date in the MCP server.
- MTTA (`createdAt` → `acknowledgedAt`) and MTTR (→ `resolvedAt`) need one `GET /incidents/{id}` per incident.
  Escalation, hand-over and reopen counts need the history (`GET /incidents/{id}/history`).
- Every result says how many incidents it is based on. Results are limited (default 50 rows) so they fit in a
  model's context.
- **Revisit** when the data grows past a few hundred incidents, or when a UI needs the same numbers. Then add query
  parameters and a stats endpoint to incident-service (§6), and the MCP tools call those instead.

## 5. Consequences

### Positive

- Incident work, status checks and analytics in natural language, with no change to the domain.
- The ADR-0001 boundaries hold: one more client of the public contracts, removable at no cost.
- Each user sees only relevant tools, so there are fewer wrong calls and less confusion for the model.
- Business rules stay in one place. When incident-service changes a rule, the MCP server follows without a change,
  and its error messages explain the new rule.
- The two new endpoints (D9) also help Swagger, Postman and a future UI.

### Negative / risks

- **Role-based tools are not security** while `X-User-Id` is unauthenticated (D4). Anyone can configure any user.
- **Analytics do not scale:** N + 1 calls per question (D7). This is fine at demo size and is the trigger for a
  stats endpoint.
- **History lags a few seconds** behind a write (built from events, ADR-0001 D3). Tools say so, and the timeline
  marks the newest change as possibly missing.
- **Tool descriptions are a contract with the model.** A rule changed in a service but not in a description makes
  the model try invalid actions. The service still refuses them, but the experience degrades. Descriptions are
  reviewed with every API change, like the OpenAPI export.
- **The model can misread intent**, e.g. choose the wrong category or severity. This is mitigated by the triage
  prompt, by asking when unsure, by the SEV2 limit for reporters, and by confirmation hints on destructive tools.
- One more project to build and keep in sync with the copied enums.

## 6. Out of scope now / follow-up decisions

| Topic | Note |
|---|---|
| Real authentication; HTTP (streamable) transport with OAuth | Together with ADR-0001 §6. The MCP server then passes the user's token; D4 changes |
| Ops tool set: queue depths, DLQ inspect / replay / purge, outbox backlog, cache, Mailpit, container logs | A separate admin-only MCP server, with the RabbitMQ admin credentials. Not mixed with the user-facing one |
| Query parameters (`status`, `severity`, `from`, `to`, paging) and a stats endpoint in incident-service | When D7's trigger is reached |
| Read APIs for escalation records and email delivery state | Would enable "was Carol emailed?" and escalation analytics |
| Hosting the MCP server for a team | Needs authentication first |

## 7. Open questions

1. **Identity source.** Add `GET /me` to organization-service (proposed, D9), or let the MCP server read
   `/internal/actors/{user}` (rejected as O4, unless D9 is not wanted)?
2. **What reporters may see.** The API lets every user read every incident. Should a reporter's MCP tools be
   limited to their own incidents (proposed in D5), or show everything, as the API does?

## 8. Current state

A prototype exists in `mcp-server/` (2026-11-19). It has seven tools over the public APIs: `list_teams`,
`list_incidents` (team / reporter, status and severity filters), `get_incident`, `get_incident_history`,
`report_incident`, `update_incident` and `add_comment`. It covers D1–D3, D8 and D10, with 17 unit tests and a
stdio smoke test against the stack. D4–D7 and D9 are not built yet.
