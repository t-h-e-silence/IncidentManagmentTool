-- incidents: events waiting for RabbitMQ (transactional outbox, ADR D4) and idempotency keys of the public API (D9).

create table incidents.outbox
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
create index outbox_unpublished_idx on incidents.outbox (id) where published_at is null;

-- One row per (key, caller); the response is stored when the request's transaction commits.
create table incidents.idempotency_key
(
    key          varchar(100) not null,
    actor_id     uuid         not null,
    request_hash varchar(64)  not null,
    response     text,
    created_at   timestamptz  not null,
    primary key (key, actor_id)
);

create index idempotency_key_created_idx on incidents.idempotency_key (created_at);
