# AI Claims Platform

A Guidewire ClaimCenter-style insurance claims platform: Spring Boot API, Angular UI and an AI document
assistant. The claims workflow runs on a database state machine and job queue instead of a workflow
engine, and the whole system is designed to run on free-tier hosting.

> Learning project, built phase by phase. See the [build plan](docs/plan.md) for status.

## What it does (v1)

- **FNOL to closure:** a claimant reports a loss and uploads documents; the claim is triaged, assigned,
  handled through exposures, reserves and payments, and closed.
- **Real controls:** four roles (claimant, adjuster, supervisor, SIU), per-user authority limits,
  maker-checker approvals, SIU payment hold, full audit trail.
- **AI that suggests, never decides:** document classification and extraction, damage estimates and fraud
  signals from an LLM (Groq), validated in code and overridable by the adjuster with a reason.
- **Workflow without an engine:** state transitions, timers, retries and SLAs on PostgreSQL
  ([ADR-0006](docs/adr/0006-database-state-machine-instead-of-a-workflow-engine.md)).

## Stack

Java 17 · Spring Boot 3.5 · Spring Security (JWT) · Spring Data JPA · PostgreSQL 16 · Flyway · springdoc
OpenAPI · JUnit 5 · Testcontainers · ArchUnit · Docker · GitHub Actions · Angular (phase 8) ·
Vercel, Render, Supabase, Groq

## Run locally

Requires Java 17+, Maven and Docker.

```bash
docker compose up -d --wait          # PostgreSQL on localhost:5434
cd backend
mvn spring-boot:run                  # API on http://localhost:8081 (profile: local)
```

- Swagger UI: http://localhost:8081/swagger-ui.html
- Demo users: `claimant1`, `claimant2`, `adjuster1`, `adjuster2`, `supervisor1`, `siu1`, password `Password1!`

```bash
curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"adjuster1","password":"Password1!"}'
```

## Tests

```bash
cd backend
mvn test      # unit + architecture rules
mvn verify    # + integration tests on Testcontainers PostgreSQL, OpenAPI contract check, coverage
```

If the API changes on purpose, regenerate the contract and review the diff:
`mvn verify -Dopenapi.update=true`.

## Documentation

- [Build plan and status](docs/plan.md)
- [Design document (v1)](docs/design/design-document-v1.md): architecture, domain model, lifecycle,
  flows, schema, API, AI pipeline
- [Architecture overview](docs/architecture.md)
- [Architecture Decision Records](docs/adr/README.md)
- [Phase notes](docs/phases/)
- [OpenAPI contract (v1)](docs/openapi/api.v1.json)
