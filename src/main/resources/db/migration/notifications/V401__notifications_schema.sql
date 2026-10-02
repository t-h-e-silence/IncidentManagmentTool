-- notifications: one row per email, created with the action it is about and sent later by the delivery job.

create table notifications.notification
(
    id              uuid primary key,
    incident_id     uuid          not null,
    recipient_id    uuid          not null,
    recipient_email varchar(320)  not null,
    reason          varchar(30)   not null,
    subject         varchar(300)  not null,
    body            text          not null,
    status          varchar(20)   not null check (status in ('PENDING', 'RETRYING', 'SENT', 'DEAD_LETTERED')),
    attempts        int           not null default 0,
    next_attempt_at timestamptz,
    last_error      varchar(1000),
    created_at      timestamptz   not null,
    sent_at         timestamptz,
    version         bigint        not null default 0
);

create index notification_due_idx on notifications.notification (next_attempt_at)
    where status in ('PENDING', 'RETRYING');
create index notification_incident_idx on notifications.notification (incident_id);
