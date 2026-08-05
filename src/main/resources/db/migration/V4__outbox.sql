-- Messages are written here in the same transaction as the event they describe,
-- and a relay publishes them afterwards. That closes the gap where a crash
-- between commit and publish left an event stored but never evaluated.
create table outbox_message (
    id            uuid primary key     default gen_random_uuid(),
    aggregate_id  uuid         not null,
    tenant_id     uuid         not null references tenant (id) on delete cascade,
    routing_key   varchar(120) not null,
    payload       jsonb        not null,
    created_at    timestamptz  not null default now(),
    published_at  timestamptz,
    attempts      integer      not null default 0,
    last_error    text
);

-- The relay only ever asks for unpublished rows oldest first. A partial index
-- keeps that scan proportional to the backlog rather than to everything ever
-- published, which matters because published rows are the overwhelming majority.
create index idx_outbox_unpublished
    on outbox_message (created_at)
    where published_at is null;
