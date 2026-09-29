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

Stories 6, 7 and 8: *paged for a SEV1 and acknowledge it, so that escalation stops*; *reassign to another team, whose on-call is paged immediately*; *raise a SEV3 to SEV1 and the policy starts paging*.

```mermaid
sequenceDiagram
    autonumber
    actor R as Responder
    participant S as Incident Management System
    participant N as Email notifications
    actor X as Other team / duty manager

    N-->>R: Email "New incident for your team"
    R->>S: Open the team's queue
    S-->>R: Open incidents, most urgent first

    opt Urgency is higher than reported
        R->>S: Raise severity (e.g. SEV3 → SEV1)
    end

    loop SEV1: page until someone acknowledges
        S->>N: Page the on-call responder
        N-->>R: Email "SEV1 — please acknowledge"
    end

    alt Responder acknowledges in time
        R->>S: Acknowledge
        S-->>R: Paging stopped, incident in progress
    else Nobody acknowledges
        S->>S: Escalate automatically
        S->>N: Page the next level
        N-->>X: Email "SEV1 escalated to you"
    end

    opt Another team should own it
        R->>S: Reassign to another team, with a reason
        S->>N: Page that team now
        N-->>X: Email "Incident reassigned to you"
    end

    R->>S: Add investigation comments
    R->>S: Resolve with a note
    S->>N: Tell the team and the reporter
    N-->>R: Email "Incident resolved"
```
