# Incident Management System — Behavior Diagrams

How the functionality behaves, derived from [productDesign.md](productDesign.md) §8 (user stories), §9 (metrics) and §11 (open questions).
User-facing flows are in [sequenceDiagrams.md](sequenceDiagrams.md). Points marked *(assumption)* are not stated in the product design and need confirming.

## 1. Incident lifecycle

An incident is triggered by a person (USER reporter) or a monitoring system (SYSTEM reporter), acknowledged by a responder, and resolved by a responder or by the alert clearing.

```mermaid
stateDiagram-v2
    [*] --> Triggered: USER reports / SYSTEM trigger event
    Triggered --> Acknowledged: responder acknowledges
    Triggered --> Triggered: escalated (priority raised, maybe new team)
    Acknowledged --> Acknowledged: escalated, same team (priority raised)
    Acknowledged --> Triggered: escalated or reassigned to another team (new team notified)
    Triggered --> Resolved: responder resolves / SYSTEM resolve event
    Acknowledged --> Resolved: responder resolves / SYSTEM resolve event
    Resolved --> Triggered: same alert fires within 30 min of an automatic resolve (reopen)
    Triggered --> Closed: SEV4 not handled within 7 days (auto-close, open question 5)
    Resolved --> [*]
    Closed --> [*]
```

- Escalation never changes the state by itself; only a hand-over to another team puts the incident back to Triggered for the new team.
- TTA is measured from Triggered to Acknowledged; each reopen or reassignment starts a new segment.
- TTR is measured from the first trigger to the final resolve, including reopened periods.

## 2. Handling a monitoring event (SYSTEM reporter)

Events carry a `dedup_key`, so repeats of the same alert update one incident instead of creating many (stories 1–4).

```mermaid
flowchart TD
    E[Event arrives with dedup_key] --> T{Event type?}

    T -->|trigger| O{Open incident with this dedup_key?}
    O -->|yes| D[Deduplicate: attach event to the incident, no new notification]
    O -->|no| R{Resolved incident with this dedup_key?}
    R -->|auto-resolved less than 30 min ago| RO[Reopen and notify the team again]
    R -->|resolved manually, alert still firing| S[Suppress: record the repeat, no notification]
    R -->|none, or older than 30 min| N[Create new incident, route and notify]

    T -->|resolve| X{Open incident with this dedup_key?}
    X -->|yes| AR[Auto-resolve the incident, notify the team]
    X -->|no| I[Ignore, record in audit]
```

- *(assumption)* Suppression after a manual resolve lasts until the monitoring system sends a resolve event for that `dedup_key`.
- Dedup ratio = events / incidents; reopen rate = reopened / system-resolved incidents, per service.

## 3. Routing a new incident

```mermaid
flowchart TD
    A[New incident: service or category, severity] --> B{Reporter type?}
    B -->|USER| C{Severity up to SEV2?}
    C -->|no, SEV1| C1[Rejected: a USER reporter may set at most SEV2]
    C -->|yes| D
    B -->|SYSTEM| D{Specific routing rule matches?}
    D -->|yes| E[Assign to the rule's team]
    D -->|no| F[Assign to the fallback team]
    E --> G[Notify the team and check the escalation policy, see 4]
    F --> G
```

Target: more than 90% of incidents routed by a specific rule, not by the fallback team.

## 4. Escalation

Escalation means the incident needs **different handling**: it always **raises priority**, and may also hand the incident over to another team.
It is triggered by a decision or a severity change, **never by time** — an unacknowledged incident does not escalate by itself.

```mermaid
flowchart TD
    subgraph Triggers
        M[Responder or team lead escalates, with a reason]
        SV[Severity set or raised]
    end

    SV --> TH{Crosses the team policy's threshold?}
    TH -->|no| NO[No escalation, handling unchanged]
    TH -->|yes, first time for this policy| AU[Automatic escalation:<br/>policy's priority and target team]
    TH -->|already escalated by this policy| NO

    M --> HP{Priority higher than now?}
    HP -->|no| RJ[Rejected: escalation must raise priority]
    HP -->|yes| MA[Manual escalation:<br/>new priority, optionally another team]

    AU --> AP[Priority raised, team changed if needed]
    MA --> AP
    AP --> NT[Owning team and reporter notified]
    NT --> AD[Audit entry: trigger, reason, who or which policy]
```

- Moving an incident to another team **without** raising priority is a plain reassignment, not an escalation.
- Lowering severity or priority afterwards does not de-escalate.
- *(assumption)* Notification is a single email per change; there are no repeated pages or ack timeouts.

## 5. Changes on an open incident

```mermaid
flowchart TD
    A[Responder or team lead changes an open incident] --> B{What changed?}
    B -->|severity raised| C[Check escalation policy, see 4]
    B -->|severity or priority lowered| D[Updated, no de-escalation]
    B -->|escalated| C2[Priority raised, maybe new team, see 4]
    B -->|reassigned, same priority| E[New team notified,<br/>new TTA segment starts]
    B -->|resolved| F[Team and reporter notified]
    C --> G[Audit entry: who, what, when]
    C2 --> G
    D --> G
    E --> G
    F --> G
```

- Every significant action, human or automatic, gets an audit entry (target 100%), so an auditor sees the full timeline including the reporter (story 10).

## 6. Conflicts with productDesign.md

These parts of the product design assume time-based escalation and need rewording:

| Where | Current text | Conflict |
|---|---|---|
| Story 6 | acknowledge a SEV1 *so that escalation stops* | Escalation is not running in the background, so acknowledging does not stop it |
| Story 9 | duty manager paged *when nobody acknowledged a SEV1 after all repeats* | Time-based; no repeats or final fallback |
| §9 metrics | *% acknowledged at level 1*, *% escalation exhausted* | No escalation levels to measure |
| §11 Q2, Q6 | default ack timeout per severity; final fallback target | Not needed for escalation |
