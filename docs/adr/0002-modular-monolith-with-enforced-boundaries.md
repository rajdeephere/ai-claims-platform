# ADR-0002: Modular monolith with boundaries enforced by tests

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

The platform covers one business domain (claims) built by one developer, and it must run on Render's
free tier: one container with 512 MB. Microservices would need several containers, network calls between
them and distributed transactions, none of which the domain needs.

Without enforcement, though, a monolith's packages slowly depend on each other in every direction.

## Decision

One Spring Boot application, split into modules by business capability (`identity`, `claim`,
`exposure`, `payment`, `approval`, `activity`, `document`, `ai`, `siu`, `policy`, `audit`, `platform`)
plus shared `common`. Each module has the same layers:

| Layer | Holds | May depend on |
|---|---|---|
| `api` | controllers, request/response DTOs | `app`, `domain` |
| `app` | application services, transaction boundaries | `domain`, `infra` |
| `domain` | entities, state machines, business rules | nothing outer |
| `infra` | repositories, adapters for external systems | `domain` |
| `config` | Spring configuration | anything in its module |

`ArchitectureTest` (ArchUnit) fails the build if a controller uses a repository, the domain depends on an
outer layer, `common` depends on a business module, or modules form a cycle.

## Consequences

- ✅ One deployable, one database transaction per business step, simple local development.
- ✅ Boundaries are checked on every build, not left to discipline.
- ✅ A module with clean ports can be split out later if it ever needs to scale on its own.
- ⚠️ Every module scales together; acceptable for this load.
- ⚠️ ArchUnit rules must be written as methods: Surefire silently skips rules declared as fields (BUG-001).

## Alternatives considered

- **Microservices (as in ClaimFlow):** already demonstrated there; here they would add network failure
  modes and infrastructure without a business reason.
- **Spring Modulith:** stronger module support (events, documentation), but another framework to learn;
  ArchUnit covers the rules needed now.
