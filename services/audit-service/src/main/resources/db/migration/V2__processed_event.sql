-- audit: ids of incident events already recorded; RabbitMQ delivers at least once, so repeats are skipped (ADR D4).
-- Rows older than 30 days are deleted, far longer than any redelivery window.

create table audit.processed_event
(
    event_id     uuid primary key,
    processed_at timestamptz not null
);

create index processed_event_processed_idx on audit.processed_event (processed_at);
