# Incident Management System — Sequence Diagrams

What a user can do with the system, derived from the user stories in [productDesign.md](productDesign.md) §8.
Like a C4 context view, the system is one box; internal modules are not shown. **Email notifications** covers everything that informs people, including sending the email over SMTP.

## 1. User as reporter

Story 5: *As an employee (USER reporter), I create an incident for a service with a severity up to SEV2, so that the right team is engaged — and I'm told when it's acknowledged and resolved.*

```mermaid
sequenceDiagram
    autonumber
    actor U as Reporter
    participant S as Incident Management System
    participant N as Email notifications
    actor T as Responsible team

    U->>S: Report incident (title, description, category, severity up to SEV2)
    S->>S: Pick the responsible team from the category
    S-->>U: Incident created, here is its ID
    S->>N: Tell the team about the new incident
    N-->>T: Email "New incident"

    opt Reporter follows up
        U->>S: View incident and its timeline
        U->>S: Add a comment with more details
    end

    T->>S: Acknowledge
    S->>N: Tell the reporter
    N-->>U: Email "Your incident was acknowledged"

    T->>S: Resolve with a note
    S->>N: Tell the reporter
    N-->>U: Email "Your incident was resolved"
```

## 2. User as responder

Stories 7 and 8: *reassign to another team, whose on-call is notified immediately*; *raise a SEV3 to SEV1 and the policy escalates, so that urgency matches reality*.
Escalation always **raises priority** (and may move the incident to another team); it is triggered by a person or by a severity change, never by time.

```mermaid
sequenceDiagram
    autonumber
    actor R as Responder
    participant S as Incident Management System
    participant N as Email notifications
    actor X as Other team

    N-->>R: Email "New incident for your team"
    R->>S: Open the team's queue
    S-->>R: Open incidents, highest priority first
    R->>S: Acknowledge
    S-->>R: Incident in progress

    opt Urgency is higher than reported
        R->>S: Raise severity (e.g. SEV3 → SEV1)
        alt Severity crosses the team's policy threshold
            S->>S: Escalate automatically: raise priority, hand over to the policy's team
            S->>N: Tell the new owning team and the reporter
            N-->>X: Email "Escalated incident, priority P1"
        else Below the threshold
            S-->>R: Severity updated, handling unchanged
        end
    end

    opt Needs more urgent or different handling
        R->>S: Escalate with a reason (higher priority, optionally another team)
        S->>N: Tell the owning team and the reporter
        N-->>X: Email "Incident escalated to you"
    end

    R->>S: Add investigation comments
    R->>S: Resolve with a note
    S->>N: Tell the team and the reporter
    N-->>R: Email "Incident resolved"
```
