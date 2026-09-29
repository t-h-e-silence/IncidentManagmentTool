2. *As a monitoring system, I send a resolve event with the same dedup_key, so that the incident auto-resolves when the problem clears.*
3. *As a responder who resolved an incident, I'm paged if the same alert fires again within 30 minutes after an automatic resolve, so that I know the problem came back.*
4. *As a responder who resolved an incident manually while the alert was still firing, I'm not paged again by the alert's repeats while I verify the fix.*
5. *As an employee (USER reporter), I create an incident for a service with a severity up to SEV2, so that the right team is engaged — and I'm told when it's acknowledged and resolved.*
6. *As the on-call responder, I'm paged for a SEV1 and acknowledge it, so that escalation stops.*
7. *As a team lead, I reassign an acknowledged incident to the DB team, and their on-call is paged immediately.*
8. *As a responder, I raise a SEV3 to SEV1 and the policy starts paging, so that urgency matches reality.*
9. *As a duty manager, I'm paged when nobody on a team acknowledged a SEV1 after all repeats.*
10. *As an auditor, I see the full timeline of an incident, including its reporter and every automatic decision.*

## 9. Success metrics

| Metric | Definition | MVP target |
|---|---|---|
| Time to acknowledge (TTA) | Triggered → first acknowledgement; each reopen or reassignment starts a new measured segment | SEV1 < 5 min for ≥ 90% |
| Time to resolve (TTR) | Triggered → final resolve, including reopened periods | Measured per severity, trend down |
| % acknowledged at level 1 | Share of high-urgency incidents acknowledged before first escalation | ≥ 80% |
| % escalation exhausted | Share of high-urgency incidents reaching final fallback | ≈ 0 |
| Dedup ratio (SYSTEM) | Events / incidents | Tracked — shows noise reduction |
| Reopen rate (SYSTEM) | Reopened / system-resolved incidents, per service | Tracked — shows unstable services |
| % routed by specific rule | Not by fallback team | > 90% |
| Audit completeness | Significant actions with an audit entry | 100% |

## 10. Roadmap (indicative)

1. MVP — §7.
2. On-call — schedules, rotations, overrides, business hours and time-of-day urgency; first real delivery channel; personal notification rules.
3. Noise control — alert grouping, event rules, maintenance windows, flapping detection.
4. Communicate — status pages, stakeholder subscriptions.
5. Learn — post-incident reviews, analytics, tamper-evident audit.

## 11. Open questions

1. Should a USER reporter be able to withdraw their own incident as a false alarm before it's acknowledged?
2. Default ack timeout per severity (proposed: SEV1 30 min, SEV2 60 min)?
3. Flapping guard: after N reopens in a period, raise severity, flag as "flapping", or suppress reopens?
4. Can later SYSTEM events change severity on an open incident, or only the first event sets it?
5. SEV4 auto-close period — 7 days acceptable?
6. Who is the final fallback target in practice (duty manager rota, head of engineering)?
7. Interface for responders in MVP: API only, minimal web UI, or chat bot?
8. Tech constraints: language, hosting, database, budget.