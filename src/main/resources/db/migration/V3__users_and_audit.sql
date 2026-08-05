-- Humans who administer a tenant. Distinct from api_key, which authenticates
-- machines: different lifecycle, different credential type, different threat
-- model. One scheme for both would be wrong for at least one of them.
create table app_user (
    id            uuid primary key     default gen_random_uuid(),
    tenant_id     uuid         not null references tenant (id) on delete cascade,
    email         varchar(255) not null,
    password_hash varchar(72)  not null,
    role          varchar(20)  not null,
    created_at    timestamptz  not null default now(),

    constraint chk_user_role check (role in ('ADMIN', 'ANALYST', 'VIEWER'))
);

-- Email is unique per tenant, not globally: the same person may administer
-- more than one tenant, and a global constraint would leak the existence of
-- accounts across customers.
create unique index uq_user_tenant_email on app_user (tenant_id, lower(email));

create table audit_log (
    id        uuid primary key    default gen_random_uuid(),
    tenant_id uuid        not null references tenant (id) on delete cascade,
    actor_id  uuid,
    action    varchar(80) not null,
    resource  varchar(255),
    at        timestamptz not null default now()
);

create index idx_audit_tenant_at on audit_log (tenant_id, at desc);
