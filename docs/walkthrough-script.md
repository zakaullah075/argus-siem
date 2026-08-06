# Walkthrough recording script

Four minutes. Screen recording with your voice — no face needed, no editing.
Record it in one take; a second take is always better than a polished first one.

**Tools:** OBS Studio, or Windows `Win+G`, or Loom. Upload unlisted to YouTube and
put the link at the top of the README and in your CV.

**Before you start:** wake the instance (load the page once), sign in as your own
ADMIN account with the rules already created, and have a terminal open in
`/mnt/e/java_mastry/argus`.

---

## 0:00 — What it is (20 seconds)

> "This is Argus, a multi-tenant security monitoring platform I built in Spring
> Boot. Servers report events to it — failed logins, privilege escalation — and
> it decides which of them are worth alerting on. It's live, so everything you're
> about to see is running in production."

Screen: the dashboard, signed in.

---

## 0:20 — The problem (30 seconds)

> "Two things usually go wrong with tools like this. The first is alert fatigue:
> one brute-force attempt produces two hundred identical alerts, and analysts
> learn to ignore the system. The second is silent loss — a crash between
> accepting an event and evaluating it, and an attack is never noticed, with no
> error anywhere. The design is built around those two failures."

Screen: stay on the dashboard, or the README's problem section.

---

## 0:50 — Watch it work (60 seconds)

Click **SSH brute force**. Wait.

> "That sent six failed logins for one account. The API returned immediately —
> two hundred and two, accepted — and the detection ran afterwards, in a
> separate consumer. Here's the alert."

Click it **again**. Point at the count.

> "Second burst. Notice there's still one alert and the count went from two to
> four. That's deduplication — it keys on the rule plus the actor, so a sustained
> attack stays one alert that keeps counting. Two different accounts under
> attack stay separate, because that's genuinely two different problems."

Click **Routine noise**.

> "And this is traffic that matches no rule. Events are recorded — you can see
> them in the events tab — but no alert. Anything can raise alerts. Not raising
> them is the hard part."

---

## 1:50 — The architecture decision (60 seconds)

Screen: the README diagram, or `EventService.java`.

> "The part I'd point at in a code review is how the event gets from the API to
> the detector. The obvious approach is to publish to the queue after the
> database transaction commits — but that leaves a window where a crash loses
> the evaluation silently. The event is stored and nothing ever looks at it."
>
> "So instead the message row is written *inside* the same transaction as the
> event. They commit together or not at all. A relay publishes it afterwards.
> That's the transactional outbox pattern."
>
> "The trade-off is that delivery becomes at-least-once — a crash after the
> broker accepts a message but before the row is marked will republish it. I took
> that deliberately: a duplicate just folds into the existing alert and costs
> nothing, while losing one means an intrusion goes unnoticed."

---

## 2:50 — Multi-tenancy (30 seconds)

Screen: `V5__row_level_security.sql`.

> "It's multi-tenant, and isolation is enforced twice. Every query is scoped by
> tenant, and Postgres row-level security sits underneath as a backstop. One
> forgotten WHERE clause in one repository method would otherwise return another
> customer's security events, silently. With RLS that same mistake returns zero
> rows instead. Defence in depth, where the failure mode is catastrophic and
> invisible."

---

## 3:20 — Proof (30 seconds)

Terminal:

```bash
python3 scripts/smoke.py
```

> "And this is how I verify it. Twenty-five checks against the live deployment,
> not against mocks — idempotent ingest, alert folding, pagination, the error
> shape, cross-tenant isolation, and that a revoked key stops working
> immediately. About a hundred and twenty tests behind it too, with Testcontainers
> running real Postgres and real RabbitMQ rather than H2."

Let a few PASS lines scroll.

---

## 3:50 — Close (15 seconds)

> "Spring Boot 3.5, Java 21, Postgres, RabbitMQ, deployed with GitHub Actions.
> The README documents the decisions I rejected and the limitations it still has.
> Link's in the description — thanks for watching."

---

## Rules for the recording

- **Say the trade-off, not just the choice.** "I used an outbox" is a fact.
  "I used an outbox because publishing after commit loses events silently, and
  I accepted at-least-once delivery for it" is an engineer.
- **Don't apologise** for the free-tier delay, the UI, or anything else. State
  limitations flatly, the way the README does.
- **Don't read this script aloud.** Learn the beats, then talk. Slightly rough
  and genuinely understood beats smooth and recited — and one is much easier to
  tell apart than you'd think.
- **Stop at four minutes.** Nobody watches longer.
