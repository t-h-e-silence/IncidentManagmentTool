# ADR-0001: Migrate the modular monolith to event-driven microservices

- **Status:** Proposed
- **Moved:** the decision record, its diagrams and the microservices themselves now live in their own repository,
  **`incident-management`** (`docs/adr/0001-migrate-to-microservices.md` there).

This monolith is frozen and stays as the reference implementation: the microservices reuse its business rules and
keep its public API (paths, bodies, status codes), each service on its own port.
