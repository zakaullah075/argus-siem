# Argus

**A multi-tenant security monitoring platform.** Servers report what happens on
them — failed logins, privilege escalation, new sessions — and Argus decides
which of it is worth waking someone up for.

[![CI](https://github.com/zakaullah075/argus-siem/actions/workflows/ci.yml/badge.svg)](https://github.com/zakaullah075/argus-siem/actions/workflows/ci.yml)

Spring Boot 3.5 · Java 21 · PostgreSQL · RabbitMQ

---

## Try it now

**Live: [argus-siem.onrender.com](https://argus-siem.onrender.com)**

Sign in to the read-only demo and press the traffic buttons to watch the
pipeline run end to end:

```
demo@argus.dev  /  demo1234
```

Or verify it yourself in 90 seconds — no setup, standard library only:

```bash
python3 scripts/smoke.py
```

That runs 25 checks against the live deployment: idempotent ingest, alert
folding, pagination, error shape, tenant isolation and key revocation.

**API reference:** [Swagger UI](https://argus-siem.onrender.com/swagger-ui.html)

> Free hosting, so the first request may take ~50 seconds to wake the instance.

---

## The problem it solves

A single server writes thousands of log lines a day. Almost all of it is noise,
and the few lines that matter — someone trying passwords against `root`, an
account suddenly running commands as administrator — look exactly like the rest
until you already know what you are looking for.

Two things usually go wrong with the tools that try to help:

**They alert on everything.** One brute-force attempt produces two hundred
identical alerts, analysts start ignoring the system, and the real intrusion
arrives inside a wall of noise nobody reads.

**They lose events quietly.** A crash between accepting an event and evaluating
it means an attack is never noticed, and nothing anywhere reports an error.

Argus is built around those two failures. Repeat detections **fold into one
alert whose count rises** rather than piling up. And an event is never
acknowledged unless the instruction to evaluate it was committed in the same
database transaction, so a crash cannot silently drop it.

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

Detection is data, not code. Rules are rows — change a threshold and behaviour
changes with no deployment.

---

## Monitor a real machine

The agent is a single standard-library Python file, about 200 lines, and worth
reading before you run anything as root.

```bash
curl -O https://argus-siem.onrender.com/agent/argus-agent.py

export ARGUS_API_KEY=<the key you were shown once>
python3 argus-agent.py --test                       # prove connectivity

python3 argus-agent.py --follow /var/log/auth.log
```

Fail an SSH login a few times, or run a `sudo` command, and an alert appears on
the dashboard.

It matches six patterns out of syslog — failed passwords, invalid users,
accepted logins, sudo escalation, session opens and PAM failures — and ignores
everything else. Two details worth knowing:

**It generates the event id itself.** A retry after a timeout resends the same
id, and the server treats it as a duplicate rather than recording the event
twice — which is what the idempotent ingest API exists for.

**It reopens the file when the inode changes.** Without that, log rotation leaves
the agent holding a handle to a file nobody writes to any more: it goes quiet and
never reports an error.

---

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
| GET | `/actuator/health` `/actuator/prometheus` | — | |

Roles: **ADMIN** manages everything · **ANALYST** works alerts · **VIEWER** reads.

Generated reference at [`/swagger-ui.html`](https://argus-siem.onrender.com/swagger-ui.html).

---

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

---

## Tests

```bash
./mvnw verify          # tests, then static analysis
python3 scripts/smoke.py http://localhost:8080
```

Around 120 tests across three levels:

- **Unit** — detection matching, windowing, dedupe keys, key hashing, rate
  limiting. No Spring, no database, milliseconds.
- **Architecture** — ArchUnit rules that fail the build if a controller reaches a
  repository, an entity leaks through an endpoint, field injection appears, or a
  cycle forms between feature packages.
- **Integration** — Testcontainers, real Postgres and real RabbitMQ. H2 would not
  enforce the `jsonb` column, the severity check constraint or the unique index,
  so it could pass while production failed.

Plus **`scripts/smoke.py`**, which asserts the same guarantees against a
*running deployment* rather than a test fixture — the difference between "it
works on my machine" and "it works".

SpotBugs runs at `verify` and fails the build on new findings. Every exclusion in
`spotbugs-exclude.xml` is justified individually rather than blanket-suppressed.

Detection assertions poll, because evaluation happens in a consumer. Where a test
proves something did *not* happen, it waits for the queue to drain first —
otherwise it would pass simply by checking too early.

---

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

**The message is written in the same transaction as the event.** A transactional
outbox, with a relay publishing afterwards — so the row and the instruction to
evaluate it commit together or not at all. Publishing after commit instead would
leave a window where a crash loses the evaluation silently.

**Tenant isolation is enforced twice.** Every query is scoped, and Postgres row
level security makes a forgotten predicate fail closed rather than return another
customer's security events.

**Detection windows are closed at both ends.** An open upper bound let events
that happened *after* the one being evaluated count towards its threshold, so
"3 failures in 60 seconds" fired on three failures five minutes apart. Invisible
while evaluation was synchronous.

**Alert deduplication keys on rule + actor.** Once a burst trips a threshold,
every later event trips it too. Without folding, one brute-force attempt produces
hundreds of identical alerts, and analysts learn to ignore the system.

**Severity is matched against an explicit set, not `>=`.** The column stores enum
names, so a relational comparison would order them alphabetically —
`CRITICAL < HIGH < LOW < MEDIUM`. Wrong, and silently so.

**Email is normalised with `Locale.ROOT`.** The default locale lowercases `I` to
a dotless `ı` in Turkish, so signup would store an address login could never find.
Static analysis found this one.

**Packaging is by feature, not by layer.** `ingest/`, `rules/`, `alerts/` rather
than `controller/`, `service/`, `repository/`. A change stays in one folder, and
the structure describes what the system does rather than which framework it uses.

---

## Known limitations

Stated rather than hidden:

- **Delivery is at-least-once.** The outbox closes the lost-message gap, but a
  crash after the broker accepts a message and before the row is marked will
  republish it. Detection folds a repeat into the existing alert, so a duplicate
  costs nothing — losing one would mean an attack goes unnoticed.
- **Rate limiting is per instance.** In-memory counters mean two instances allow
  roughly twice the configured rate. The fixed window also permits a burst across
  the boundary.
- **Row level security does nothing if the application connects as a superuser.**
  Policies are enabled and forced, but superusers and `BYPASSRLS` roles ignore
  them unconditionally. The migration creates an `argus_app` role for this; a
  deployment that ignores it falls back to predicate-only isolation.
- **One count query per matching rule, per event.** Fine at a handful of rules per
  tenant; at hundreds this becomes the bottleneck and should move to rolling
  counters in Redis.
- **JWTs cannot be revoked before expiry.** One hour is the exposure window.
- **Tenant rate limits are cached without invalidation** — a change takes effect
  on restart.
- **The free-tier deployment stalls under sustained load.** One small instance
  and a small connection pool; the fix is a paid instance and tuned pooling, not
  a code change.

---

## What I would do next

In the order I would actually do it:

1. **Rolling counters in Redis** for detection, removing the per-rule count query
   that becomes the bottleneck first.
2. **Distributed rate limiting**, so the limit is real rather than per instance.
3. **A refresh-token flow** with a revocation list, closing the one-hour window.
4. **Alert routing** — email, Slack, webhook — because an alert nobody sees is
   the same as no alert.
5. **Rule backtesting**: run a candidate rule against historical events and show
   what it would have fired on, so tuning does not require waiting for an attack.

---

## Not built

No machine-learning anomaly detection — rules only. No log-shipping agents beyond
the Python one; HTTP ingest otherwise. No billing.
