# ADR-0002 diagrams: the MCP server, the user and the system

**Belongs to:** [ADR-0002: Add an MCP server so AI assistants can do incident work](0002-add-mcp-server.md)
**Date:** 2026-11-20 · **Status:** Proposed (same as the ADR)

## 1. Who talks to whom

The user talks to an AI assistant in plain language. The assistant calls the MCP server's tools. The MCP server calls
only the **public** REST APIs, as the configured user. Everything after that (events, emails, history) happens inside
the system, exactly as for any other client.

Arrows: **solid = calls**, **dotted = what comes back to the user**.

```mermaid
flowchart LR
    user(["User<br/>e.g. bob, dan"])
    ai["AI assistant<br/>(Claude Code / Desktop / IDE)"]
    mcp["mcp-server<br/>(acts as INCIDENT_MCP_USER)"]

    subgraph system["Incident management system"]
        org["organization-service<br/>:8081 GET /teams"]
        inc["incident-service<br/>:8082 incidents, comments"]
        aud["audit-service<br/>:8083 history"]
        mq{{"RabbitMQ<br/>events"}}
        noti["notification-service"]
    end

    mail["Mailpit<br/>(emails)"]

    user -->|"plain language"| ai
    ai -->|"MCP tool calls (stdio)"| mcp
    mcp -->|"HTTP + X-User-Id"| org
    mcp -->|"HTTP + X-User-Id"| inc
    mcp -->|"HTTP + X-User-Id"| aud
    inc --> mq --> aud
    mq --> noti --> mail

    ai -.->|"answer"| user
    mail -.->|"email to team"| user
```

The MCP server never touches databases, RabbitMQ, Redis or `/internal/**`.

## 2. "Create me an incident": one request, step by step

```mermaid
sequenceDiagram
    actor U as User (bob)
    participant A as AI assistant
    participant M as mcp-server
    participant I as incident-service
    participant R as RabbitMQ → notification, audit

    U->>A: "Create an incident: checkout takes 10 s,<br/>many customers affected"
    A->>A: picks title, category, severity (SEV2)
    opt something unclear
        A->>U: "Payments or Web application?"
        U->>A: "Payments"
    end
    A->>M: report_incident(title, categoryId, SEV2, description)
    M->>I: POST /incidents (X-User-Id: bob, Idempotency-Key)
    alt allowed
        I-->>M: 201 incident (OPEN, owning team)
        I--)R: incident.created → emails to team, history entry
        M-->>A: the incident
        A-->>U: "Created INC …, Platform team, SEV2, OPEN"
    else rule broken (e.g. SEV1 by a reporter)
        I-->>M: 409 problem
        M-->>A: tool error: "409 from incident-service: …"
        A-->>U: explains, suggests SEV2
    end
```

The service decides what is allowed; the MCP server only passes the request on and explains the answer.

## 3. At start-up: the user's role decides the tools

```mermaid
flowchart TD
    start["MCP client starts mcp-server<br/>INCIDENT_MCP_USER=…"]
    me["GET /me (organization-service)<br/>role + teams"]
    unknown["Refuse to start:<br/>unknown or inactive user"]
    admin{"system role<br/>ADMIN?"}
    team{"member of<br/>a team?"}

    rep["Reporter tools<br/>report, edit / comment own,<br/>my incidents, own stats"]
    resp["Responder tools<br/>+ team queue, acknowledge, resolve, close,<br/>escalate / hand over, team analytics"]
    adm["Admin tools<br/>global analytics, any incident,<br/>system status"]

    start --> me
    me -->|"401 / 404"| unknown
    me --> admin
    admin -->|yes| adm
    admin -->|no| team
    team -->|no| rep
    team -->|yes| resp
    adm -.->|"also in a team"| resp
```

The assistant only ever sees the tools of its user's role. The services still check every request (ADR-0002 D4,
D5).
