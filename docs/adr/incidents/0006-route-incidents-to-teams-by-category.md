# ADR-0006: Route incidents to teams by category

## Status
Proposed — 2026-09-25. Amended by [ADR-0011](0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md): the owning team can change through escalation.

## Context
Reporters don't know how the organization is structured. Incidents must reach the responsible team immediately, and a reorganization shouldn't require retraining reporters.

## Decision
- The reporter selects a **Category** (e.g. "Payments", "Database", "VPN").
- `organization` maps each category to exactly one team (`Category.teamId`).
- At creation, `incidents` calls `teamForCategory(categoryId)` and stores `teamId`, `categoryId` and a category-name snapshot. It never copies member lists.
- Incidents are assigned to a **team only** (no individual assignee or on-call rotation in v1).
- Only **active** categories can be chosen. A team cannot be archived while an active category routes to it, so `teamForCategory` always returns an active team ([ADR-0015](../data/0015-deactivate-or-archive-referenced-data-instead-of-deleting-it.md)).

## Consequences
- **Easier:**
  - reporters use familiar terms;
  - a reorganization only means remapping categories;
  - past incidents keep their original team.
- **Harder:**
  - a wrong mapping sends incidents to the wrong team. The receiving team fixes it by reassigning the incident ([ADR-0011](0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md)), and the category is remapped for future incidents;
  - there is no per-person ownership or on-call yet.
- **Follow-on ADRs:** individual assignment / on-call rotation, if needed later.

## Alternatives considered
- **Reporter picks the team directly:** rejected because it requires knowing the organization's structure.
- **Assign to an individual responder:** rejected for v1 because of scope; it would need on-call rules.

## Confirmation
- An integration test: creating an incident with category C stores the team currently mapped to C.
- Creating an incident with an inactive category is rejected with `UnknownCategoryException`.
