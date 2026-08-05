# ADR 008 — Row level security for tenant isolation

**Status:** accepted · strengthens [ADR 003](003-shared-schema-multi-tenancy.md)

## Context

Shared-schema tenancy put every customer's security events in one set of tables,
with isolation resting entirely on every query carrying its `tenant_id`
predicate. ADR 003 named the risk plainly: a repository method that forgets one
leaks data silently. It returns rows — just the wrong customer's — with a 200 and
no error anywhere.

For a product holding authentication failures and privilege escalations, that is
the worst failure the system can have.

## Decision

Postgres row level security on every tenant-scoped table, with the tenant
supplied per connection from the authenticated credential.

A `TenantContextFilter` publishes the tenant resolved from the API key or the JWT
claim — never from anything a caller can choose. A `BeanPostProcessor` wraps the
`DataSource` Spring Boot built and applies `set_config('argus.tenant_id', …)` on
every checkout, so the policy covers queries nobody remembered to scope,
including ones written later by someone who has never read that class.

The policy is permissive when no tenant is set, because the outbox relay and
Flyway run without one and must keep working. The guarantee is for request-scoped
access.

## Consequences

**Three things had to be true before any of it did anything**, and getting the
first two wrong produced a configuration that looked complete and protected
nothing:

1. `ENABLE ROW LEVEL SECURITY` does not apply to the table owner. `FORCE` is also
   required.
2. **Superusers and `BYPASSRLS` roles ignore policies entirely, and that cannot
   be overridden.** The application must connect as a plain role.
3. `WITH CHECK` as well as `USING`, or isolation covers reads and not writes.

Testcontainers connects as a superuser, so the first working version passed
inspection — policies present, `relforcerowsecurity` true, session variable set,
and `select count(*)` still returning every tenant's rows. The tests now connect
as a dedicated non-superuser role, because a test that cannot observe the policy
is not testing it.

**Isolation is now defence in depth, not a single point.** The query predicate
and the database policy would both have to be wrong.

**There is a kill switch.** `argus.rls.enabled=false` reverts to predicate-only
isolation. A policy misconfiguration would otherwise make the application appear
to have lost all its data, and the fix for that must not require a code change.

**Deployments that connect as a superuser get nothing from this.** Documented in
the README rather than assumed, because it is invisible when wrong.
