-- audit: append-only log of significant actions. The application never updates or deletes rows;
-- the trigger below makes that a database guarantee too.

create table audit.audit_entry
(
    id             uuid primary key,
    actor_id       uuid         not null,
    action         varchar(50)  not null,
    entity_type    varchar(30)  not null,
    entity_id      uuid         not null,
    incident_id    uuid,
    details        jsonb        not null default '{}',
    correlation_id varchar(100) not null,
    occurred_at    timestamptz  not null
);

create index audit_entry_incident_idx on audit.audit_entry (incident_id, occurred_at);
create index audit_entry_actor_idx on audit.audit_entry (actor_id, occurred_at);

create function audit.reject_change() returns trigger
    language plpgsql as
$$
begin
    raise exception 'audit entries are append-only';
end;
$$;

create trigger audit_entry_append_only
    before update or delete
    on audit.audit_entry
    for each row
execute function audit.reject_change();
