# ADR-0004: Keep users and teams in one organization module

## Status
Proposed — 2026-09-25

## Context
The brief suggests a `teams` module. We also need users. A separate `identity` module was considered, because every module may become a microservice. After per-team roles ([ADR-0005](0005-assign-roles-per-team-not-per-user.md)), user data is very small (`id`, `name`, `email`, `systemRole`), and it is almost always used together with team data:
- notifications need "members of team X with their contacts";
- authorization needs both the system role and the team role.

## Decision
- One **`organization`** module (schema `organization`) owns `User`, `Team`, `Membership` and `Category`.
- Public API: `teamForCategory(categoryId)`, `roleOf(teamId, userId)`, `membersOf(teamId)` (members with contacts), `userById(userId)`.
- Users live in an internal sub-package, `organization.user`, with no dependency on team classes, so they can be lifted out later.
- Users are seeded. How the current user is identified is decided in [ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md).

## Consequences
- **Easier:**
  - fewer modules and synchronous dependencies;
  - one call gives members with contacts;
  - it stays close to the brief's module list and keeps the first implementation in scope.
- **Harder:**
  - the module has two reasons to change (identity data vs routing rules);
  - personal data sits next to routing configuration;
  - adopting an external IdP requires extracting `organization.user`.
- **Revisit when:** an external identity provider (Keycloak, Auth0) is adopted, or user/profile features grow independently of team routing.

## Alternatives considered
- **Separate `identity` and `teams` modules:** clearer separation and easier IdP replacement. Rejected for now because `identity` would be almost empty and every main use case would need both modules.
- **Two modules sharing the same user tables:** rejected (see [ADR-0003](../data/0003-give-each-module-its-own-database-schema.md)).
- **External IdP now:** out of scope for the first implementation ([ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md)).

## Confirmation
An ArchUnit rule: `organization.user..` must not depend on `organization.team..`.
