# ADR-0013: Authenticate users and authorize incident access by team

## Status
Proposed — 2026-09-25

## Context
The rules for who may do what were spread over several ADRs, and some were missing:
- [ADR-0004](../organization/0004-keep-users-and-teams-in-one-organization-module.md) accepted an `X-User-Id` header as a thin-slice shortcut. Any client that sends it can act as anyone, **including an `ADMIN`**, and nothing stops the shortcut from reaching a shared environment.
- [ADR-0005](../organization/0005-assign-roles-per-team-not-per-user.md) defined the roles but only said who may *change* an incident, not who may *see* one.
- [ADR-0011](../incidents/0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md) needs to know what the previous team may do after a reassignment.
- A reporter who is not a team member could not add information to their own incident.
- [ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md) sends incidents of a team without active members to the admins, but admins had no right to act on them.

The system is an internal tool, and during an outage people from many teams need to see what is going on.

## Decision
**Authentication**
- The `X-User-Id` header is accepted **only when the `local` or `test` Spring profile is active**. In any other profile, the header filter is not registered, and the application refuses to start unless an OIDC issuer is configured.
- Later: an OAuth2 resource server validates JWTs from the identity provider. The token's `email` claim is mapped to `User.id` through `organization`.
- A deactivated or unknown user gets **401**. Every request resolves to one active `User`.

**Authorization** is checked in each module's application service, using `organization.roleOf(teamId, userId)` and `User.systemRole`:

| Action | Who may do it |
|---|---|
| Report an incident | any active user |
| Read incidents, comments and the audit timeline | any active user |
| Comment | members of the owning team, and the incident's **reporter** |
| Change status, severity or priority; resolve | members of the owning team (`RESPONDER` or `TEAM_LEAD`) |
| Escalate: reassign or raise priority | members of the owning team; **`ADMIN`** for any open incident |
| Manage teams, categories and escalation policies | `ADMIN` |
| Replay dead letters | `ADMIN` |

- "Owning team" always means the **current** `team_id`. After a reassignment, the previous team keeps read access like everyone else, but can no longer change the incident.
- `TEAM_LEAD` has **no extra rights in v1**. Candidates for later: resolving SEV1, lowering severity, reassigning.
- `ADMIN` does not get general write access to incidents. The only exception is reassigning, so an incident stuck in a team without active members can always be moved.
- Errors: **401** when unauthenticated, **403** when the user is known but not allowed. The 403 response doesn't reveal more than a read would.

## Consequences
- **Easier:**
  - the insecure header can't reach a shared environment by accident;
  - one table answers "who may do what", and every row can be tested;
  - transparency during outages: anyone can follow an incident without being added to a team;
  - reporters can add details to their own incidents.
- **Harder:**
  - no confidential incidents yet: everyone sees everything, including security incidents;
  - authorization checks sit in several modules' services and must stay aligned with this table;
  - admin reassignments are rare but powerful, so the audit entry must show the admin as the actor.
- **Follow-on ADRs:**
  - OIDC provider choice (Keycloak, Auth0);
  - confidential incidents with restricted read access;
  - `TEAM_LEAD`-only actions.

## Alternatives considered
- **Read access only for the owning team, previous teams and the reporter:** rejected for v1. It hides incidents that affect everyone during an outage, and it needs history-aware checks after reassignments. Revisit with confidential incidents.
- **`ADMIN` may do everything:** rejected. Changes to incidents should come from the people handling them, and the audit trail should show that.
- **Keep the `X-User-Id` header everywhere until OIDC arrives:** rejected. Anyone who can reach the API could impersonate an admin.
- **Spring Security method annotations with SpEL on every endpoint:** rejected as the only mechanism. The rules need data from `organization`, so they read better as one policy class per module, which is also easier to unit-test.

## Confirmation
- A Spring context test with the `prod` profile and no OIDC issuer fails to start. With the `local` profile, the header works.
- One authorization test per row of the table, including:
  - a member of another team gets 403 on status change;
  - the reporter can comment but not resolve;
  - an admin can reassign an incident but not resolve it;
  - after a reassignment, the previous team gets 403 on changes and 200 on reads.
- A deactivated user gets 401.
