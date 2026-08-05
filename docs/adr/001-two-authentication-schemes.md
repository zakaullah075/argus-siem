# ADR 001 — Two authentication schemes

**Status:** accepted

## Context

Argus has two kinds of caller with genuinely different needs.

Agents and applications push events continuously, unattended, from machines that
may run for years. Humans log in from a browser to manage rules and work alerts.

A single scheme has to be wrong for one of them. Short-lived tokens force
machines into a refresh dance they have no user to complete. Long-lived
credentials for humans mean a stolen token stays valid indefinitely.

## Decision

Two schemes, split by audience.

**Machines** use API keys in an `X-Api-Key` header. Long-lived until explicitly
revoked. Stored as a SHA-256 hash.

**Humans** use JWTs obtained by logging in with email and password. One hour TTL.
Passwords stored with BCrypt.

The hashing difference is deliberate. An API key is 256 bits from a CSPRNG, so
there is no dictionary to attack and a deliberately slow hash buys nothing — it
would only add cost to the hot path, since keys are verified on every ingest
request. A password is low-entropy and human-chosen, so the hash must be slow to
make offline guessing expensive.

## Consequences

Two code paths to maintain and reason about, and the request-matching between
them must be exact. This bit us once: broadening the API key filter from
`/v1/events` to `/v1/` swallowed `/v1/auth/login`, so obtaining a token required
a token. The matcher is now explicit and the trap documented in the filter.

Revoking a JWT before expiry is not possible without a denylist, which is not
built. One hour is the exposure window for a stolen token. Acceptable now;
if it stops being acceptable the answer is shorter TTLs plus refresh tokens,
not a lookup on every request.
