-- Tenant isolation was enforced only by every query remembering its tenant_id
-- predicate. That works until one forgets, and the failure is silent: the query
-- returns rows, just the wrong customer's. Row level security makes that case
-- fail closed instead.
--
-- The application connects as a role subject to these policies and sets
-- argus.tenant_id per request. A query missing its predicate now returns nothing
-- rather than another tenant's security events.

create or replace function argus_current_tenant() returns uuid as $$
    -- 'true' makes the setting optional: background work such as the outbox
    -- relay and Flyway run with no tenant set, and must not error.
    select nullif(current_setting('argus.tenant_id', true), '')::uuid;
$$ language sql stable;

do $$
declare
    target text;
begin
    foreach target in array array['event', 'alert', 'rule', 'api_key', 'app_user', 'audit_log']
    loop
        execute format('alter table %I enable row level security', target);

        -- FORCE as well as ENABLE. Policies do not apply to the table owner by
        -- default, and the application usually connects as the owner — so
        -- without this the policies exist, look correct, and do nothing. That
        -- is the common way row level security ships broken.
        execute format('alter table %I force row level security', target);

        -- Deliberately permissive when no tenant is set, so scheduled work and
        -- migrations still function. The guarantee is for request-scoped access,
        -- where the tenant always comes from an authenticated credential.
        execute format($f$
            create policy %I_tenant_isolation on %I
            using (argus_current_tenant() is null or tenant_id = argus_current_tenant())
            with check (argus_current_tenant() is null or tenant_id = argus_current_tenant())
        $f$, target, target);
    end loop;
end $$;

comment on function argus_current_tenant() is
    'Tenant for the current transaction, set by the application from the authenticated credential. Null for background work.';

-- A role the policies actually apply to.
--
-- Superusers and roles with BYPASSRLS ignore row level security entirely, and
-- that cannot be forced off. FORCE only covers the table owner. So a deployment
-- whose application connects as a superuser — which is the default on several
-- managed providers — has policies that are correct and completely inert.
--
-- The application should connect as this role. Where it cannot, isolation falls
-- back to the query-level tenant predicate, which is where it was before.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'argus_app') then
        create role argus_app nologin;
    end if;
end $$;

grant usage on schema public to argus_app;
grant select, insert, update, delete on all tables in schema public to argus_app;
grant usage, select on all sequences in schema public to argus_app;

-- Tables created by later migrations must be reachable too, or the next
-- migration silently locks the application out.
alter default privileges in schema public
    grant select, insert, update, delete on tables to argus_app;
