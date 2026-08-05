# Argus

A multi-tenant security event platform. Agents and applications push security
events over an authenticated API; Argus normalises them, evaluates detection
rules against the stream, and raises deduplicated alerts.

Spring Boot 3.5 · Java 21 · PostgreSQL · RabbitMQ

---

## What it does

```
   agents / apps
        │  POST /v1/events            X-Api-Key
        ▼
 ┌──────────────────┐
 │  Ingest API      │  authenticate → rate limit → validate → persist → 202
 └────────┬─────────┘         (returns before rules are evaluated)
          │  publish, after the transaction commits
          ▼
     ┌──────────┐         ┌──────────────┐
     │ RabbitMQ │────────►│  Detection   │  match rules → count in window
     └────┬─────┘         │  consumer    │  → raise or fold alert
          │               └──────┬───────┘
          ▼ retries exhausted    │
     ┌──────────┐                ▼
     │   DLQ    │          ┌──────────┐
     └──────────┘          │  Alerts  │  deduplicated per rule + actor
                           └──────────┘

 ┌──────────────────┐
 │ Management API   │  rules, api keys      JWT + RBAC, audit logged
 └──────────────────┘
```

**A rule fires when N matching events land inside a rolling window.** So
"five failed logins in five minutes" is one rule, and a burst produces one alert
that keeps counting rather than one alert per event.

## Use it

**https://argus-siem.onrender.com**

Create an account, issue an API key, and point the agent at a machine:

```bash
curl -O https://argus-siem.onrender.com/agent/argus-agent.py

export ARGUS_API_KEY=<the key you were shown once>
python3 argus-agent.py --test                       # prove connectivity

sudo -E python3 argus-agent.py --follow /var/log/auth.log
```

Fail an SSH login a few times and an alert appears on the dashboard. The agent is
a single standard-library Python file — no install, ~200 lines, and worth reading
before you run anything as root.

There is also a read-only demo account (`demo@argus.dev` / `demo1234`) with
buttons that generate traffic, for looking around without connecting a machine.

## Running it locally

Requires Java 21 and Docker.

```bash
docker compose up -d          # Postgres on 5433, RabbitMQ on 5672
./mvnw spring-boot:run        # application on 8080
```

Flyway creates the schema on first start.

### Walking through it

```bash
# 1. Create a tenant — you become its administrator
TOKEN=$(curl -s -X POST localhost:8080/v1/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"organisation":"Acme","email":"you@acme.test","password":"supersecret1"}' \
  | jq -r .token)

# 2. Issue an API key for an agent — shown once, never recoverable
KEY=$(curl -s -X POST localhost:8080/v1/management/api-keys \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"agent-1"}' | jq -r .apiKey)

# 3. Create a detection rule
curl -X POST localhost:8080/v1/management/rules \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"brute force","matchSource":"sshd","matchEventType":"auth.failed",
       "minSeverity":"MEDIUM","thresholdCount":5,"windowSeconds":300,
       "alertSeverity":"CRITICAL"}'

# 4. Ship an event
curl -X POST localhost:8080/v1/events -H "X-Api-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"id":"aaaaaaaa-0000-0000-0000-000000000001","source":"sshd",
       "eventType":"auth.failed","severity":"HIGH","actor":"root",
       "target":"10.0.0.5","payload":{"attempts":5},
       "occurredAt":"2026-08-05T01:30:00Z"}'

# 5. Read alerts
curl localhost:8080/v1/alerts -H "X-Api-Key: $KEY"
```

Resending the same event `id` returns `"duplicate": true` and does not create a
second row.

## API

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/v1/events` | API key | `202`, idempotent on client-supplied id |
| GET | `/v1/events` | API key | Paginated, tenant-scoped |
| GET | `/v1/alerts` | API key | Paginated, newest first |
| POST | `/v1/auth/signup` | — | Creates a tenant, returns a JWT. Rate limited per IP. |
| POST | `/v1/auth/login` | — | Returns a JWT |
| POST | `/v1/management/rules` | JWT, ADMIN | |
| GET | `/v1/management/rules` | JWT, any role | |
| POST | `/v1/management/api-keys` | JWT, ADMIN | Key shown once |
| GET | `/v1/management/api-keys` | JWT, any role | Never returns the hash |
| DELETE | `/v1/management/api-keys/{id}` | JWT, ADMIN | Revokes immediately |
| DELETE | `/v1/management/rules/{id}` | JWT, ADMIN | Soft delete — alerts reference the rule |
| GET | `/v1/management/events` | JWT, any role | Same data as `/v1/events`, for humans |
| POST | `/v1/management/alerts/{id}/acknowledge` | JWT, ANALYST+ | |
| POST | `/v1/management/alerts/{id}/resolve` | JWT, ANALYST+ | |
| GET | `/actuator/health` `/actuator/prometheus` | — / — | |

Roles: **ADMIN** manages everything · **ANALYST** works alerts · **VIEWER** reads.

## Tests

```bash
./mvnw test
```

Around 90 tests across three levels:

- **Unit** — detection matching, windowing, dedupe keys, key hashing, rate
  limiting. No Spring, no database, milliseconds.
- **Architecture** — ArchUnit rules that fail the build if a controller reaches a
  repository, an entity leaks through an endpoint, field injection appears, or a
  cycle forms between feature packages.
- **Integration** — Testcontainers, real Postgres and real RabbitMQ. H2 would not
  enforce the `jsonb` column, the severity check constraint or the unique index,
  so it could pass while production failed.

Detection assertions poll, because evaluation happens in a consumer. Where a test
proves something did *not* happen, it waits for the queue to drain first —
otherwise it would pass simply by checking too early.

## Design decisions

Fuller versions, including the alternatives rejected, are in [docs/adr](docs/adr).

**Flyway owns the schema; Hibernate is `ddl-auto: validate`.** Hibernate only
checks the entities match what migrations produced. It caught a real `char(64)`
vs `varchar` mismatch on first boot.

**The primary key is the idempotency guarantee.** Clients supply the event id, so
a retrying agent collides instead of duplicating.

**Two authentication schemes.** API keys for machines (SHA-256: 256 random bits
need no slow hash, and this runs on every request). BCrypt for passwords
(low-entropy and human-chosen, so slow is the point).

**Publishing happens after commit, not inside the transaction.** A rollback must
not announce an event that was never stored. The reverse gap — a crash between
commit and publish — is real and needs a transactional outbox to close; that is
noted in the code rather than pretended away.

**Alert deduplication keys on rule + actor.** Once a burst trips a threshold,
every later event trips it too. Without folding, one brute-force attempt produces
hundreds of identical alerts, and analysts learn to ignore the system.

**Severity is matched against an explicit set, not `>=`.** The column stores enum
names, so a relational comparison would order them alphabetically —
`CRITICAL < HIGH < LOW < MEDIUM`. Wrong, and silently so.

**Packaging is by feature, not by layer.** `ingest/`, `rules/`, `alerts/` rather
than `controller/`, `service/`, `repository/`. A change stays in one folder, and
the structure describes what the system does rather than which framework it uses.

## Known limitations

Stated rather than hidden:

- **No transactional outbox.** A crash between commit and publish leaves an event
  stored but unevaluated.
- **Rate limiting is per instance.** In-memory counters mean two instances allow
  roughly twice the configured rate. The fixed window also permits a burst across
  the boundary.
- **Tenant isolation is enforced in queries, not by the database.** Postgres
  row-level security would make a forgotten predicate fail closed instead of
  leaking. Covered by a test; not yet enforced structurally.
- **One count query per matching rule, per event.** Fine at a handful of rules per
  tenant; at hundreds this becomes the bottleneck and should move to rolling
  counters in Redis.
- **JWTs cannot be revoked before expiry.** One hour is the exposure window.
- **Tenant rate limits are cached without invalidation** — a change takes effect
  on restart.

## The agent

`src/main/resources/static/agent/argus-agent.py` — standard library only, so it
runs anywhere with Python 3.8+.

It matches six patterns out of syslog: failed passwords, invalid users, accepted
logins, sudo escalation, session opens, and PAM failures. Everything else is
ignored.

Two details worth knowing:

**It generates the event id itself.** A retry after a timeout resends the same
id, and the server treats it as a duplicate rather than recording the event
twice — which is what the idempotent ingest API exists for.

**It reopens the file when the inode changes.** Without that, log rotation leaves
the agent holding a handle to a file nobody writes to any more: it goes quiet and
never reports an error.

## Not built

No UI beyond the dashboard. No machine-learning anomaly detection — rules only. No
log-shipping agents; HTTP ingest only. No billing or signup.
