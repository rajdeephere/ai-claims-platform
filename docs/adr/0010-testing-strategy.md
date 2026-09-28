# ADR-0010: Testing strategy

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Most of the value (and risk) is in rules: state transitions, authority limits, maker-checker, token
rotation. Some behaviour exists only with the real stack: Flyway migrations, SQL constraints, security
filters, JSON serialisation.

## Decision

| Level | Tool | Runs in | Covers |
|---|---|---|---|
| Unit (`*Test`) | JUnit 5, Mockito, AssertJ | Surefire, `mvn test` | domain rules, services with mocked repositories, token handling, filters |
| Architecture | ArchUnit (rules as methods) | Surefire | module and layer boundaries |
| Integration (`*IT`) | Spring Boot on a random port + Testcontainers PostgreSQL 16 | Failsafe, `mvn verify` | full HTTP requests through security, Flyway, JPA, the real database |
| Contract | committed OpenAPI file | Failsafe | the published API |

- One Spring context for all integration tests (the same base class and configuration), so one
  container starts per build.
- Integration tests assert the response body (`code`, fields), not only the status.
- A fixed `Clock` is injected everywhere time matters.
- CI runs `mvn test`, then `mvn verify -Dskip.unit=true`, and JaCoCo merges both into one report.

## Consequences

- ✅ Rules are fast to test; the real database catches what mocks can't.
- ⚠️ Integration tests need Docker.

## Alternatives considered

- **H2 in-memory database:** different SQL dialect; partial indexes and check constraints behave
  differently from PostgreSQL.
- **MockMvc only:** skips the real HTTP layer (ClaimFlow found a PATCH bug that MockMvc hid).
