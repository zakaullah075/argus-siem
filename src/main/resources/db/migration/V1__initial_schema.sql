create table tenant (
    id                    uuid primary key     default gen_random_uuid(),
    name                  varchar(120) not null,
    plan                  varchar(30)  not null default 'free',
    rate_limit_per_minute integer      not null default 600,
    created_at            timestamptz  not null default now()
);

-- The key itself is never stored. We keep a SHA-256 hash and show the plaintext
-- once, at creation — same reasoning as passwords. A database leak must not hand
-- the attacker working credentials.
create table api_key (
    id           uuid primary key    default gen_random_uuid(),
    tenant_id    uuid        not null references tenant (id) on delete cascade,
    key_hash     varchar(64) not null unique,
    name         varchar(120) not null,
    last_used_at timestamptz,
    revoked_at   timestamptz,
    created_at   timestamptz not null default now()
);

create index idx_api_key_tenant on api_key (tenant_id);

create table event (
    id          uuid primary key,
    tenant_id   uuid         not null references tenant (id) on delete cascade,
    source      varchar(120) not null,
    event_type  varchar(120) not null,
    severity    varchar(20)  not null,
    actor       varchar(255),
    target      varchar(255),
    raw_payload jsonb        not null,
    occurred_at timestamptz  not null,
    ingested_at timestamptz  not null default now(),

    constraint chk_event_severity
        check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

-- Every query is scoped to one tenant and almost always ordered by time, so the
-- composite index matches the real access pattern. tenant_id alone would still
-- force a sort on every page of results.
create index idx_event_tenant_occurred on event (tenant_id, occurred_at desc);
create index idx_event_tenant_type on event (tenant_id, event_type);
