# Argus

A multi-tenant security event platform. Agents and applications push security
events over an authenticated API; Argus validates, stores and (from Phase 2)
evaluates detection rules against the stream to raise alerts.

## Architecture

```
   agents / apps
        │  POST /v1/events        X-Api-Key header
        ▼
 ┌──────────────────┐
 │  Ingest API      │  authenticate → validate → persist → 202 Accepted
 └────────┬─────────┘
          ▼
     ┌──────────┐
     │ Postgres │   tenants, api keys, events (raw payload as jsonb)
     └──────────┘
```

Planned: async processing via RabbitMQ, a rule engine, alert deduplication, and
a JWT-authenticated management API. See `docs/` for the full spec.

## Running it

Requires Java 21 and Docker.

```bash
docker compose up -d          # Postgres on localhost:5433
./mvnw spring-boot:run        # application on localhost:8080
```

Flyway creates the schema on first start.

### Creating a tenant and key

There is no management API yet, so seed one directly:

```sql
insert into tenant (id, name, plan, rate_limit_per_minute)
values ('11111111-1111-1111-1111-111111111111', 'Acme Corp', 'free', 600);
```

Then issue a key through `ApiKeyService.issue(tenantId, name)`. The plaintext key
is returned once and never stored — only its SHA-256 hash is kept.

### Sending an event

```bash
curl -X POST http://localhost:8080/v1/events \
  -H "X-Api-Key: $ARGUS_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{
        "id": "aaaaaaaa-0000-0000-0000-000000000001",
        "source": "sshd",
        "eventType": "auth.failed",
        "severity": "HIGH",
        "actor": "root",
        "target": "10.0.0.5",
        "payload": {"attempts": 5, "port": 22},
        "occurredAt": "2026-08-05T01:30:00Z"
      }'
```

Response is `202 Accepted`. Resending the same `id` returns `"duplicate": true`
and does not create a second row.

## Tests

```bash
./mvnw test
```

Integration tests run against a real Postgres started by Testcontainers, not an
in-memory database — H2 would not enforce the `jsonb` column, the severity check
constraint, or the unique index, so it could pass while production failed.

## Design decisions

**Flyway owns the schema; Hibernate is `ddl-auto: validate`.** Hibernate only
checks that the entities match what the migrations produced. Letting it alter a
schema is how production drifts away from source control.

**Client-supplied event IDs.** The primary key is the idempotency guarantee — a
retrying agent resends the same ID and collides instead of duplicating.

**SHA-256 for API keys, not BCrypt.** Keys are 256 bits of random data, so there
is no dictionary to attack and no need for a deliberately slow hash. This runs on
every request, where BCrypt's cost would be the bottleneck. Passwords are the
opposite case and require BCrypt.

**Raw payloads stored verbatim as `jsonb`.** Normalisation is lossy; when a
detection rule misfires, the original event is the only way to find out why.

**The tenant comes from the API key, never the request body.** A caller must not
be able to write into another tenant by naming it.

## Status

Phase 1 complete: schema, API key authentication, tenant isolation, validated
idempotent ingest, paginated queries, integration tests.

Not yet built: async pipeline, rule engine, alerting, management API, rate
limiting, metrics dashboards.
