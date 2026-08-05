# ADR 005 — Detection moves to a consumer

**Status:** accepted · supersedes [002](002-synchronous-detection.md)

## Context

ADR 002 kept rule evaluation on the request thread and named the trigger for
revisiting it: ingest latency, since evaluation costs one count query per
matching rule. With rules, alerts and dedup in place, the cost of leaving it
there was clear — a tenant adding rules slows down every agent shipping events
to it, and a slow rule blocks the caller.

## Decision

Ingest persists the event, publishes a message and returns. A `@RabbitListener`
consumer evaluates rules.

**The message carries only the event id.** The row is already durable, so the
consumer reads the canonical record rather than a copy that could disagree with
it. It also keeps messages small and avoids versioning a payload schema across
the queue. The cost is one extra read per message.

**Publishing happens on `AFTER_COMMIT`, not inside the transaction.** Publishing
inside would announce an event that a rollback then erased, and the consumer
would look for a row that never existed.

**JSON on the wire, not Java serialization.** The default Spring AMQP converter
cannot handle a `record`, and it would tie the message format to one class on
both sides — a consumer could never be rewritten in another language, and adding
a field would break messages already in flight.

**Failures retry four times with exponential backoff, then dead-letter.**
`default-requeue-rejected: false`, because requeueing a poison message spins it
forever and blocks everything behind it, which looks identical to an outage.

## Consequences

**The dual-write window was narrowed, not closed** — and has since been closed
by [ADR 007](007-transactional-outbox.md). At the time of this decision a crash
between commit and publish left an event stored and never evaluated.

**Publish failures are logged, not thrown.** The event is already committed;
failing the request would tell the caller to retry something that succeeded,
producing a duplicate. This is the right trade-off and it has a cost: when the
message converter was misconfigured, every publish failed silently and detection
did nothing while ingest kept returning `202`. Only a positive test caught it —
every "no alert was raised" test passed happily against a completely dead
pipeline.

**Alerting is now eventually consistent.** An event is queryable before it has
been evaluated. Tests that assert an alert must poll; tests that assert no alert
must first wait for the queue to drain, or they pass by checking too early.

**Re-evaluation is safe.** A redelivered message folds into the existing alert
rather than creating another, so at-least-once delivery does not produce
duplicate alerts.
