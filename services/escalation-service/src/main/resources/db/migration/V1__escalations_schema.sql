-- escalations: history of escalations and de-escalations, recorded from incident events. Incident, actor and team ids
-- belong to other services and are kept as ids only.

create table escalations.escalation
(
    id            uuid primary key,
    incident_id   uuid          not null,
    actor_id      uuid          not null,
    reason        varchar(2000) not null,
    from_severity varchar(10)   not null check (from_severity in ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    to_severity   varchar(10)   not null check (to_severity in ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    from_team_id  uuid          not null,
    to_team_id    uuid          not null,
    escalated_at  timestamptz   not null
);

create index escalation_incident_idx on escalations.escalation (incident_id, escalated_at);
