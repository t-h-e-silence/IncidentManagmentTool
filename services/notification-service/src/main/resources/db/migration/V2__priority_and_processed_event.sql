-- notifications: urgent emails (SEV1 / SEV2) are sent first (ADR D8); ids of consumed messages, so a repeat delivery
-- by RabbitMQ creates no second email (ADR D4). Marks older than 30 days are deleted.

alter table notifications.notification
    add column priority varchar(10) not null default 'NORMAL' check (priority in ('URGENT', 'NORMAL'));

drop index notifications.notification_due_idx;
create index notification_due_idx on notifications.notification (priority, next_attempt_at)
    where status in ('PENDING', 'RETRYING');

create table notifications.processed_event
(
    event_id     uuid primary key,
    processed_at timestamptz not null
);

create index processed_event_processed_idx on notifications.processed_event (processed_at);
