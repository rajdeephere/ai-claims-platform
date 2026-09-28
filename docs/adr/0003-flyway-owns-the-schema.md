# ADR-0003: Flyway owns the schema; demo data is a separate location

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Hibernate can create or update tables itself (`ddl-auto: update`). That hides schema changes from review
and behaves differently from one environment to the next. The demo also needs seed users, which a real
deployment must not get.

## Decision

- Every schema change is a versioned Flyway migration in `db/migration`. Hibernate only validates
  (`ddl-auto: validate`) in every profile, so an entity that doesn't match the schema fails at startup.
- Demo data (users with a known password) lives in `db/demo`, included by default through
  `FLYWAY_LOCATIONS`. A real deployment sets `FLYWAY_LOCATIONS=classpath:db/migration`.
- Demo migrations use sub-versions (`V1_1`) so they run right after the schema they need.

## Consequences

- ✅ Schema changes are reviewed as SQL, with constraints the database enforces (check constraints,
  partial unique indexes) that JPA annotations can't express.
- ✅ Local, test (Testcontainers) and production databases are built the same way.
- ⚠️ Removing `db/demo` from a database that already applied it makes Flyway report a missing migration;
  a real deployment must decide at its first start.

## Alternatives considered

- **`ddl-auto: update`:** no review, no rollback plan, never drops or renames safely.
- **Liquibase:** equivalent; Flyway's plain SQL is simpler to read.
