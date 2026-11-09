-- incidents: lifecycle OPEN -> IN_PROGRESS -> IN_REVIEW -> RESOLVED -> CLOSED, or CANCELLED.

alter table incidents.incident
    drop constraint incident_status_check;

alter table incidents.incident
    add constraint incident_status_check
        check (status in ('OPEN', 'IN_PROGRESS', 'IN_REVIEW', 'RESOLVED', 'CLOSED', 'CANCELLED'));

alter table incidents.incident
    add column closed_at timestamptz;

create index incident_created_idx on incidents.incident (created_at);
