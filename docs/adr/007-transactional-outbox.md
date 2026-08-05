# ADR 007 — Transactional outbox

**Status:** accepted · closes the gap left by [005](005-async-detection-pipeline.md)

## Context

ADR 005 moved detection into a consumer and published on `AFTER_COMMIT`, which
removed one failure direction: a rollback could no longer announce an event that
was never stored.

The opposite direction remained. Between the transaction committing and the
message reaching the broker there is a window where the process can die. The
event is durable; the instruction to evaluate it is not. Nothing reports an
error, because from the caller's point of view ingest succeeded — it returned
`202` and the row exists.

For a security product that failure is worse than it sounds. The missing
evaluation is silent, and the thing not evaluated might be the attack.

## Decision

The message is written to an `outbox_message` table in the same transaction as
the event. A scheduled relay publishes unpublished rows and marks them.

**Claimed with `PESSIMISTIC_WRITE` and skip-locked semantics**, so more than one
instance of the relay can run without publishing the same message twice: each
skips rows another has claimed rather than blocking behind them.

**`OutboxWriter` is `Propagation.MANDATORY`.** With `REQUIRED` a caller outside a
transaction would commit the outbox row on its own, and the atomicity this exists
to provide would be silently absent. Failing loudly beats appearing to work.

**A failed publish leaves the row unpublished**, records the error, and the batch
continues. One poisonous message must not stall every other tenant's events
behind it.

**Published rows are purged after seven days.** A table that only grows makes the
partial index scan slowly stop being cheap.

## Consequences

**Delivery is now at-least-once rather than at-most-once.** A crash after the
broker accepts a message but before the row is marked will republish it. That is
the correct direction to fail: detection folds a repeat into the existing alert,
so a duplicate costs nothing, while a lost message means an attack goes
unnoticed.

**Publishing is no longer immediate.** It waits for the next relay poll, one
second by default. Detection was already asynchronous, so this changes the size
of a delay that already existed rather than introducing one.

**The outbox is shared state across a test suite.** The relay runs continuously,
so a message left by one test will be delivered into the next. Integration tests
now clear it in setup — discovered the way these things usually are, by a test
that failed for a reason unrelated to what it was testing.
