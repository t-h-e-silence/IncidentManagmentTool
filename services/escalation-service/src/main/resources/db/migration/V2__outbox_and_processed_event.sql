-- escalations: email commands waiting for RabbitMQ (transactional outbox, ADR D4), and ids of incident events already
-- recorded, so a repeat delivery creates no second record or email. Published rows are deleted after 7 days, marks
-- after 30 days.

create table escalations.outbox
(
    id             bigint generated always as identity primary key,
    event_id       uuid         not null unique,
    exchange       varchar(100) not null,
    routing_key    varchar(200) not null,
    type           varchar(100) not null,
    correlation_id varchar(100) not null,
    occurred_at    timestamptz  not null,
    payload        text         not null,
    published_at   timestamptz
);

-- The relay reads only unpublished rows, in insertion order.
create index outbox_unpublished_idx on escalations.outbox (id) where published_at is null;

create table escalations.processed_event
(
    event_id     uuid primary key,
    processed_at timestamptz not null
);

create index processed_event_processed_idx on escalations.processed_event (processed_at);
