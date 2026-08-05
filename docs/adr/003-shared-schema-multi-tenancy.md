# ADR 003 — Shared schema multi-tenancy

**Status:** accepted

## Context

Multiple customers' security events live in one system. The standard options:

1. **Database per tenant** — strongest isolation, highest operational cost.
   Migrations run N times, connection pools multiply, onboarding provisions
   infrastructure.
2. **Schema per tenant** — good isolation, still N migrations, and Postgres
   degrades with thousands of schemas.
3. **Shared schema with a tenant column** — one migration, one pool, cheapest
   onboarding. Isolation depends entirely on every query being correct.

Security event data is sensitive: one tenant seeing another's authentication
failures is a serious breach, not a cosmetic bug.

## Decision

Shared schema. Every table carries `tenant_id`, and it is always the leading
column of the composite indexes.

Isolation is enforced by making the tenant impossible to choose:

- For machine requests, the tenant comes from the authenticated API key
- For human requests, it comes from the validated JWT claim
- No endpoint accepts a tenant id in a body, path, or query parameter

## Consequences

The isolation guarantee is only as good as the queries. A repository method that
forgets its `tenant_id` predicate leaks data silently — it returns results, just
the wrong ones, and no test that checks "did I get data back" would catch it.

Mitigations in place: a test that ingests events for two tenants and asserts each
sees exactly one, and the rule that the tenant is never taken from client input.

Now also in place: Postgres row-level security, added in
[ADR 008](008-row-level-security.md). A forgotten predicate fails closed instead
of leaking. Hibernate filters were rejected as a weaker version of the same idea,
since native queries bypass them.

Accepted risk: shared infrastructure means one tenant's ingest volume can affect
another's latency. Per-tenant rate limiting exists in the schema
(`rate_limit_per_minute`) but is not yet enforced.
