# ADR-0001: Use a modular monolith

## Status
Proposed — 2026-09-25

## Context
The system covers several sub-domains: organization (users and teams), incidents, escalations, notifications and audit. The assignment brief requires strong internal boundaries but forbids microservices at this stage. The team is a single developer working on a one-week timeline, the domain is still being discovered, and boundaries are likely to move. Every module should nevertheless be extractable into its own service later.

## Decision
- Build one deployable **Spring Boot 3 / Java 21** application, with one top-level package per module: `organization`, `incidents`, `escalations`, `notifications`, `audit`.
- Each module exposes only an `api` package (interfaces, DTOs, events). Its domain, persistence and web code are internal.
- Modules interact only through another module's `api` (synchronous calls) or through domain events ([ADR-0007](0007-query-synchronously-publish-side-effects-asynchronously.md), [ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md)).

## Consequences
- **Easier:**
  - one build and one deployment;
  - simple local development and debugging;
  - in-process calls with no network failures;
  - a clear extraction path: a module's `api` becomes a REST or messaging contract.
- **Harder:**
  - a bug or memory leak in one module affects the whole process;
  - modules can't be scaled or deployed independently;
  - boundaries depend on discipline plus automated checks.
- **Follow-on ADRs:** data ownership ([ADR-0003](../data/0003-give-each-module-its-own-database-schema.md)), sync vs async communication ([ADR-0007](0007-query-synchronously-publish-side-effects-asynchronously.md)).

## Alternatives considered
- **Microservices now:** rejected. The operational cost (deployment, network failures, distributed tracing) is too high while boundaries are still being discovered, and the brief forbids it.
- **Layered monolith (controller/service/repository):** rejected. It has no domain boundaries and tends to become tangled over time.

## Confirmation
A Spring Modulith test, `ApplicationModules.of(App.class).verify()`, fails the build on dependency cycles or on access to another module's internal packages.

**Not in place yet:** `spring-modulith-starter-test` must be added to `pom.xml` (test scope) before this check exists.
