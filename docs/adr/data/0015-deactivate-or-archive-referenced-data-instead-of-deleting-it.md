# ADR-0015: Deactivate or archive referenced data instead of deleting it

## Status
Proposed — 2026-09-25

## Context
Modules reference each other's data by plain id, without foreign keys ([ADR-0003](0003-give-each-module-its-own-database-schema.md)). An incident stores `team_id`, `category_id` and `reporter_id`; notifications store `recipient_id`; audit stores `actor_id` and `entity_id`. If the owning module deleted such a row, those ids would point to nothing: timelines couldn't show a name, and old incidents couldn't show their team.

This rule was only written in the system design. It also left open:
- how personal data can be erased;
- what happens to open incidents of an archived team;
- whether internal tables (outbox, idempotency keys) grow forever.

## Decision
- **Rows referenced by other modules are never hard-deleted.**

  | Data | Instead of delete | Effect |
  |---|---|---|
  | `User` | deactivate (`deactivated_at`) | can't log in or act, isn't notified, isn't returned by `membersOf` |
  | `Team` | archive (`archived_at`) | receives no new incidents or escalations; its open incidents stay with it |
  | `Category` | deactivate (`active = false`) | can't be chosen by reporters |
  | `EscalationPolicy` | deactivate (`active = false`) | no longer fires |
  | `Incident` | resolve | read-only, kept forever |

- **`organization` guards the links it owns:**
  - a team can't be archived while an active category routes to it;
  - a user can't be deactivated while they are the last active member of a team;
  - at least one active `ADMIN` must remain.
- **Open incidents of an archived team stay with that team,** and its members keep their rights. Archiving only stops *new* work from arriving. Admins may reassign the incidents ([ADR-0013](../security/0013-authenticate-users-and-authorize-incident-access-by-team.md)).
  - `organization` does not check for open incidents or escalation policies, because asking `incidents` or `escalations` would create a dependency cycle ([ADR-0001](../architecture/0001-use-a-modular-monolith.md)).
- **`escalations` reacts to archiving.** `organization` publishes `TeamArchived`. `escalations` consumes it and deactivates every policy that belongs to or targets that team.
  - If an automatic escalation still reaches an archived target before that happens, `IncidentApi.escalate` returns `NOT_APPLIED` ([ADR-0011](../incidents/0011-assign-incidents-to-teams-and-escalate-by-reassignment-or-priority.md)), with a WARN log and the `escalations_not_applied_total{reason="target_archived"}` metric.
- **Personal data erasure anonymizes the user and keeps the id:**
  - `name` becomes "Deleted user", and `email` becomes `deleted+<id>@invalid`;
  - contact fields are cleared;
  - `deactivated_at` is set.

  Other modules hold only the id ([ADR-0010](../audit/0010-make-the-audit-log-append-only.md): payloads have no personal data), so nothing else changes.
- **Internal tables with no outside references are purged by a scheduled job:**
  - `outbox` and `outbox_delivery` rows with all deliveries `SENT`, after 7 days;
  - `processed_events` after 30 days, which is well beyond the longest redelivery window (about 21 minutes of retries plus manual replays);
  - `notification` rows are kept, because the incident timeline shows them. Their retention is decided together with audit retention.

## Consequences
- **Easier:**
  - ids in other modules always resolve, so timelines and old incidents stay readable;
  - re-organizations are safe: archive a team, remap its categories, and history is untouched;
  - erasure requests are handled in one place (`organization`) without touching audit.
- **Harder:**
  - every query in `organization` must filter out inactive or archived rows where appropriate;
  - archived teams may still hold open incidents until someone reassigns them. A report or metric of open incidents per archived team is needed;
  - the purge job's windows must stay longer than any retry or replay window.
- **Follow-on ADRs:** audit and notification retention.

## Alternatives considered
- **Hard delete with cascading cleanup events** (e.g. `UserDeleted` consumed by every module): rejected. Every module would need cleanup logic, audit would have to change entries, and history would be lost.
- **Soft delete with a generic `deleted` flag on every table:** rejected. Deactivated, archived and inactive mean different things for each entity, and a generic flag hides that.
- **Block archiving a team while it has open incidents or is a policy target:** rejected. It needs `organization` to ask `incidents` or `escalations`, which creates a dependency cycle.

## Confirmation
Unit tests on `organization`:
- archiving a team with an active category is rejected, and archiving publishes `TeamArchived`;
- deactivating the last active member of a team is rejected;
- anonymizing a user keeps the id and clears name, email and contacts.

Integration tests:
- the purge job removes only `SENT` outbox rows older than 7 days;
- an incident of an archived team can still be changed by its members;
- after `TeamArchived`, the policies that belong to or target that team are inactive.
