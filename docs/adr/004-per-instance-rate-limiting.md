# ADR 004 — Per-instance in-memory rate limiting

**Status:** accepted, with known limits

## Context

Shared-schema tenancy means one tenant's ingest volume can degrade another's
latency — the noisy neighbour problem named in ADR 003. The tenant table already
carries `rate_limit_per_minute`; nothing enforced it.

The options were a distributed counter in Redis, or in-memory counters per
instance.

## Decision

In-memory fixed-window counters, one window per tenant, behind a two-method
interface (`tryAcquire`, `evictExpired`).

Redis was rejected for now on the grounds that it adds a dependency and an
operational failure mode to solve a problem this deployment does not yet have:
there is one instance. Adding it now would mean the limiter also has to answer
"what happens when Redis is down" — fail open and the limit is decorative, fail
closed and a cache outage becomes a total outage.

Tenant limits are cached in memory to avoid a database read per request.

## Consequences

**The limit is per instance.** Two instances behind a load balancer allow roughly
twice the configured rate. Three allow three times. This is wrong the moment the
service scales horizontally, which is why the interface is deliberately narrow —
moving the counter to Redis should not touch any caller.

**A fixed window permits a boundary burst.** The last second of one window plus
the first second of the next can pass 2× the limit in a two-second span. A
sliding window or token bucket removes that, at the cost of more state per
tenant. Accepted because the limit exists to prevent one tenant saturating the
service, not to bill precisely.

**Cached limits have no invalidation.** Changing a tenant's limit takes effect on
restart. Acceptable because limits change rarely; the fix is a TTL, and it lands
with the Redis migration.

**Windows are evicted on demand.** A map keyed by tenant that never removes
entries is a slow leak that only appears in processes that stay up for weeks —
exactly the ones where it matters.
