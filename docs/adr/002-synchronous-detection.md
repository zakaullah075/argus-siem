# ADR 002 — Detection runs synchronously, for now

**Status:** superseded by [005](005-async-detection-pipeline.md)

## Context

Ingest accepts an event and rules must be evaluated against it. That evaluation
can happen on the request thread, or the event can be queued and processed by a
consumer.

Queuing is the eventual answer for a system taking thousands of events per
second. It is also more moving parts: a broker to operate, delivery semantics to
reason about, a dead-letter path, and a visible gap between "accepted" and
"evaluated".

## Decision

Evaluate on the request thread for now.

The endpoint still returns `202 Accepted` rather than `201`, because the
asynchronous model is where this is going and the contract should not have to
change when it gets there. Callers are already told the event is accepted, not
that it has been fully processed.

## Consequences

Ingest latency includes rule evaluation — one count query per matching rule.
With a handful of rules per tenant that is a few milliseconds. It scales badly:
at hundreds of rules it becomes the dominant cost of every request, and a slow
rule blocks the agent that sent the event.

The trigger to revisit is ingest p99 latency, not a date. When it moves, the
change is to publish to a queue after persisting and evaluate in a consumer.
Because the endpoint already returns 202 and the primary key already provides
idempotency, that change does not alter the API contract or the delivery
guarantees — which is the reason for choosing those two things early.

Rejected alternative: introducing the broker now. It would have added
operational surface before there was any measurement showing it was needed, and
"we added Kafka because the diagram looked better" is not an engineering
decision.
