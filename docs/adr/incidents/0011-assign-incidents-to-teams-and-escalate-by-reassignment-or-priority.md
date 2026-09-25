# ADR-0011: Assign incidents to teams and escalate by reassignment or priority

## Status
Proposed — 2026-09-25. Amends [ADR-0006](0006-route-incidents-to-teams-by-category.md): the owning team can now change, through escalation.

## Context
Three questions were still open:
- **Who owns an incident?** The system design proposed an individual `assignee_id`. [ADR-0006](0006-route-incidents-to-teams-by-category.md) rejected per-person ownership for v1 because it needs on-call rules. The team decides internally who works on what.
- **Should an incident keep a priority?** Severity describes *impact* and is set by the reporter. The team needs its own lever for *urgency* and queue order, e.g. a SEV3 that blocks a release.
- **What does escalation do?** The system design (§8) treated escalation as a marker plus a notification. The original team stayed the owner, and the target team also got write access. That leaves two teams able to change one incident, and neither clearly responsible (domain-model open question 2).

In practice, a team escalates in two ways: it hands the incident to a team that can fix it, or it makes the incident more urgent. Both are changes to incident data, so both must follow the single-writer rule from [ADR-0003](../data/0003-give-each-module-its-own-database-schema.md).

## Decision
- **Team ownership only.** An incident belongs to exactly one team (`team_id`). There is no individual assignee. The first team comes from the category ([ADR-0006](0006-route-incidents-to-teams-by-category.md)).
- **Priority is kept.** `priority` is `P1..P4`. It defaults from severity (SEV1 → P1 … SEV4 → P4) and drives the team's queue order. Changing severity does not change priority automatically.
- **Escalation is one of two actions on an open incident, or both at once:**
  1. **Reassign** it to another team. `team_id` changes, the new team becomes the only owner, and the previous team loses write access. Read access is decided in [ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md).
  2. **Raise priority**, e.g. P3 → P2. Lowering priority is an ordinary change (`IncidentPriorityChanged`), not an escalation.
- **Triggers:**
  - *Manual:* any member of the owning team, with a required `reason`. Every member may do it, because during an incident waiting for a lead costs time. Each escalation is audited with actor and reason, and `TEAM_LEAD`-only restrictions can be added later ([ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md)).
  - *Automatic:* the escalation policy of the team that **owns the incident when severity changes** (the `teamId` in the event) fires when severity crosses its threshold. It reassigns the incident to the policy's target team and raises priority to at least the severity's default.
    - Each policy fires at most once per incident (`UNIQUE(incident_id, policy_id)`).
    - Chains are therefore possible (A → B → C, if B's threshold is crossed later) but always end: a loop A → B → A stops at the second hop because A's policy has already fired.
- **`incidents` is the only writer of `team_id` and `priority`.** `IncidentApi.escalate(incidentId, targetTeamId?, priority?, reason, actor)` runs in one transaction. It:
  - checks that the actor is a member of the owning team, or is the SYSTEM actor;
  - checks the target team through `organization`: it must exist and not be archived;
  - applies what still makes sense:
    - reassign only if the target differs from the current team;
    - raise priority only if the requested priority is higher;
  - if something was applied, updates the incident (`escalation_level + 1`, `escalated_at`) and writes `IncidentEscalated` to the incidents outbox.
- **Nothing to do vs error.** The incident may be `RESOLVED`, or already owned by the target team with at least the requested priority, or the target team may have been archived in the meantime ([ADR-0015](../data/0015-deactivate-or-archive-referenced-data-instead-of-deleting-it.md)).
  - For a **manual** call this is an error (409), so the user sees that nothing changed.
  - For an **automatic** call (SYSTEM actor with a `decisionId`), the result is `NOT_APPLIED`, which counts as success. A resolve or a manual escalation that got there first is a normal race, so it must not retry or dead-letter.
  - A repeated call with the same `decisionId` returns the first result.
- **Concurrent changes** are caught by optimistic locking on the incident `version`.
  - A manual call that loses the race gets 409 and the user retries.
  - The automatic step is retried by the escalations outbox, and on the next attempt it sees the new state.
- **`escalations` keeps policies and automatic decisions only.**
  - It consumes severity events in `aggregateVersion` order and ignores stale ones ([ADR-0007](../architecture/0007-query-synchronously-publish-side-effects-asynchronously.md), [ADR-0008](../messaging/0008-publish-domain-events-through-a-transactional-outbox.md)).
  - It records an `EscalationDecision` (`UNIQUE(incident_id, policy_id)`).
  - An outbox step then calls `IncidentApi.escalate` as SYSTEM, idempotent by `decisionId` ([ADR-0007](../architecture/0007-query-synchronously-publish-side-effects-asynchronously.md): commands to the owner).
- **`IncidentEscalated` moves to `incidents.api.events`**, because the module that makes the change owns its event ([ADR-0007](../architecture/0007-query-synchronously-publish-side-effects-asynchronously.md)). Payload: from/to team, from/to priority, trigger (`MANUAL`, `SEVERITY_THRESHOLD`), reason, and `decisionId` for automatic escalations.
- **Reactions:**
  - `notifications` notifies the members of the new owning team, or of the owning team when only priority was raised (reason `INCIDENT_ESCALATED`);
  - on a reassignment, `notifications` also tells the **reporter** which team now handles the incident. The previous team is not notified: it either made the change itself, or its own policy did it;
  - `audit` records the escalation;
  - `escalations` updates `open_incident` (team, priority, version).
- **Status is unaffected.** OPEN stays OPEN, and IN_PROGRESS stays IN_PROGRESS.

## Consequences
- **Easier:**
  - there is exactly one owner at any time, so authorization stays `roleOf(team_id, actor)`;
  - manual and automatic escalation follow one code path and produce one event;
  - an incident and its escalation commit together, with no cross-module transaction.
- **Harder:**
  - `team_id` is no longer fixed, so "which teams worked on this" and per-team metrics (MTTA/MTTR per team) must come from the audit timeline;
  - there is no individual accountability; the team handles that outside the system;
  - teams can pass an incident back and forth. `escalation_level` makes that visible, and a limit can be added later;
  - code and design changes:
    - move `IncidentEscalated` from `escalations` to `incidents`;
    - add `priority`, `escalation_level` and `escalated_at` to `Incident`;
    - drop the proposed `assignee_id`, `IncidentAssigned` and `recordEscalation`;
    - update system-design §5.2, §7 and §8.
- **Follow-on ADRs:**
  - whether reassigning or setting P1 becomes `TEAM_LEAD`-only ([ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md) keeps it open to all members in v1);
  - time-based escalation (not acknowledged within N minutes);
  - de-escalation back to the previous team.

## Alternatives considered
- **Individual assignee within the team:** rejected for v1. It needs on-call rules and duplicates what teams already organize themselves ([ADR-0006](0006-route-incidents-to-teams-by-category.md)).
- **Escalation as a notification plus marker, with a fixed owner:** rejected. Two teams could change the incident, and ownership was unclear.
- **`escalations` writes `team_id` and `priority` itself:** rejected. It breaks single-writer data ownership ([ADR-0003](../data/0003-give-each-module-its-own-database-schema.md)) and would need two transactions for one change.
- **Separate `IncidentReassigned` and `IncidentPriorityRaised` events:** rejected. Every consumer would have to know which changes count as escalations; one event states it explicitly.

## Confirmation
- Unit tests on `Incident.escalate`:
  - it reassigns and raises priority;
  - manual calls reject the same team with no priority raise, a lower or equal priority, and a `RESOLVED` incident;
  - automatic calls return `NOT_APPLIED` in the same cases, and a repeated `decisionId` returns the first result.
- An integration test with a policy (threshold SEV1, target team B) raises severity SEV2 → SEV1 and checks that:
  - the incident is owned by team B with priority P1;
  - exactly one `IncidentEscalated` was published;
  - each member of team B got a notification;
  - redelivering the severity event changes nothing;
  - resolving the incident before the automatic step runs leaves the step `SENT`, not `DEAD_LETTERED`.
- A Spring Modulith `verify()` test confirms that `escalations` changes incidents only through `IncidentApi`.
