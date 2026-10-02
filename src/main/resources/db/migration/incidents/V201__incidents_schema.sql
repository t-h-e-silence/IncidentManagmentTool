-- incidents: the incident and its comments. team_id, reporter_id, category_id reference other schemas by id only.

create table incidents.incident
(
    id              uuid primary key,
    title           varchar(200)  not null,
    description     varchar(5000) not null,
    category_id     uuid          not null,
    category_name   varchar(100)  not null,
    team_id         uuid          not null,
    reporter_id     uuid          not null,
    severity        varchar(10)   not null check (severity in ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    status          varchar(20)   not null check (status in ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    acknowledged_at timestamptz,
    resolved_at     timestamptz,
    resolved_by     uuid,
    resolution_note varchar(2000),
    version         bigint        not null default 0
);

create index incident_team_status_idx on incidents.incident (team_id, status);
create index incident_reporter_idx on incidents.incident (reporter_id);

create table incidents.comment
(
    id          uuid primary key,
    incident_id uuid          not null references incidents.incident (id),
    author_id   uuid          not null,
    text        varchar(5000) not null,
    created_at  timestamptz   not null
);

create index comment_incident_idx on incidents.comment (incident_id);
