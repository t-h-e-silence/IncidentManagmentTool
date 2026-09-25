# ADR-0005: Assign roles per team, not per user

## Status
Proposed — 2026-09-25

## Context
A single global `UserType` (REPORTER / RESPONDER / ADMIN) was the first idea. It fails two real cases:
- responders often **report** incidents themselves;
- one person may be a **team lead** in one team and a regular **responder** in another.

## Decision
- `User.systemRole` = `USER | ADMIN` (global). Every `USER` can report, so "reporter" is an *action*, not a stored type.
- `Membership(teamId, userId, teamRole)` with `teamRole` = `RESPONDER | TEAM_LEAD` (per team).
- `ADMIN` manages teams, categories and escalation policies (seeded in the first implementation).
- The authorization rules built on these roles (who may read, change, comment on and escalate an incident) are decided in [ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md).

## Consequences
- **Easier:**
  - anyone can report;
  - the same person can have different roles in different teams;
  - `TEAM_LEAD`-only rules can be added without a model change.
- **Harder:** authorization combines two role sources (system role and team role).
- **Follow-on ADRs:** authorization rules ([ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md)). `TEAM_LEAD` has no extra rights in v1; the model allows adding them without a schema change.

## Alternatives considered
- **Global `UserType` per user:** rejected for the reasons in Context.

## Confirmation
Unit tests on `organization`:
- `roleOf(teamId, userId)` returns the membership role, and is empty for non-members and deactivated users;
- the same user can hold `TEAM_LEAD` in one team and `RESPONDER` in another.
