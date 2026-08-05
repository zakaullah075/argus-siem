-- A rule fires when at least `threshold_count` events match its conditions
-- inside a rolling `window_seconds`. A null condition column means "any".
create table rule (
    id              uuid primary key     default gen_random_uuid(),
    tenant_id       uuid         not null references tenant (id) on delete cascade,
    name            varchar(120) not null,
    enabled         boolean      not null default true,
    match_source    varchar(120),
    match_event_type varchar(120),
    min_severity    varchar(20),
    threshold_count integer      not null default 1,
    window_seconds  integer      not null default 300,
    alert_severity  varchar(20)  not null,
    created_at      timestamptz  not null default now(),

    constraint chk_rule_threshold check (threshold_count >= 1),
    constraint chk_rule_window check (window_seconds >= 1),
    constraint chk_rule_min_severity
        check (min_severity is null or min_severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint chk_rule_alert_severity
        check (alert_severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

create index idx_rule_tenant_enabled on rule (tenant_id, enabled);

create table alert (
    id               uuid primary key    default gen_random_uuid(),
    tenant_id        uuid        not null references tenant (id) on delete cascade,
    rule_id          uuid        not null references rule (id) on delete cascade,
    status           varchar(20) not null default 'OPEN',
    severity         varchar(20) not null,
    -- Identifies "the same ongoing problem". A brute-force burst against one
    -- account must produce one alert that keeps counting, not five hundred.
    dedupe_key       varchar(255) not null,
    occurrence_count integer     not null default 1,
    first_seen_at    timestamptz not null default now(),
    last_seen_at     timestamptz not null default now(),

    constraint chk_alert_status check (status in ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'))
);

-- One open alert per dedupe key. The partial index lets a resolved alert and a
-- new open one coexist, so a recurrence after resolution starts a fresh alert
-- instead of reopening history.
create unique index uq_alert_open_dedupe
    on alert (tenant_id, dedupe_key)
    where status <> 'RESOLVED';

create index idx_alert_tenant_last_seen on alert (tenant_id, last_seen_at desc);
