# ADR 006 — One shape for every vertical slice

**Status:** accepted

## Context

The codebase was already packaged by feature rather than by layer, which is the
important half of the decision. But the inside of each slice had drifted:

- DTOs lived in three different places — `alerts/dto/`, `apikey/ApiKeyView` at
  the package root, and nested inside `AuthController` as static records
- Naming disagreed: `RuleView`, `AlertResponse`, `ApiKeyView`, `EventResponse`
  all meant the same thing; `CreateRuleCommand` and `IngestEventRequest` did too
- Mapping lived in static `from()` methods on the DTOs, so a DTO knew about its
  entity — the dependency pointing the wrong way
- A `management/` package had appeared holding four unrelated controllers. That
  is a layer-shaped package inside a feature-shaped codebase, and it meant
  changing one feature touched two directories

None of these were bugs. All of them cost a reader time, because knowing where
something lives in one slice told you nothing about the next.

## Decision

Every slice has the same anatomy, in the same order:

```
<feature>/
├── <Entity>.java             persistence model, package-private setters
├── <Feature>Repository.java  data access only
├── <Feature>Mapper.java      entity → response, request → entity
├── <Feature>Service.java     business logic and transaction boundary
├── <Feature>Controller.java  http only, speaks DTOs
├── dto/
│   ├── <Action><Feature>Request.java
│   └── <Feature>Response.java
└── exception/
    └── <Feature><Problem>Exception.java
```

**Requests are `...Request`, responses are `...Response`.** No `View`, no
`Command`, no `Dto`. One word, used consistently, is worth more than a better
word used half the time.

**Mapping moves into a `Mapper`.** A static `from()` on the DTO makes the DTO
depend on the entity, which inverts the dependency: the outward-facing type
should not know the storage type. A mapper also gives the conversion somewhere
to be tested on its own.

**Controllers live with their feature.** `RuleController` sits in `rules/`
beside the service and repository it drives. Changing how rules work touches one
directory.

**Feature exceptions live in the feature.** Only genuinely shared ones —
`NotFoundException`, `UnauthorizedException` — stay in `common/`.

## Consequences

**More files.** A mapper for a three-field record is more ceremony than a static
factory method, and taken alone it is over-engineering. The value is not in any
one mapper; it is that every slice reads identically, so a reviewer learns the
shape once.

**The rules are enforced, not documented.** ArchUnit now fails the build if a
controller lands outside a feature package, a DTO depends on an entity, or a
repository is reached from a controller. A convention that lives only in a
document is a convention that erodes.

**Rejected: keeping static `from()` methods.** They are idiomatic and shorter.
They also mean the DTO imports the entity, which makes the response type
impossible to move or reuse without dragging persistence along.

**Rejected: a shared `mapper` package.** It would recreate the layer-shaped
grouping this ADR exists to remove.
