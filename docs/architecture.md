# Architecture

A modular monolith: one Spring Boot API owns all claim state in PostgreSQL. The Angular UI, document
storage, the LLM and the stubbed external systems connect through ports. Full design:
[design/design-document-v1.md](design/design-document-v1.md).

```mermaid
flowchart TB
  UI["Angular UI · Vercel"]
  subgraph API["Spring Boot API · Render (Docker, 512 MB)"]
    WEB["Web and security<br/>REST, JWT roles, correlation ID"]
    DOM["Claim domain<br/>state machine, authority, maker-checker"]
    JOB["Job runner<br/>DB job queue, timers, outbox"]
    PORTS["Ports: repositories, documents, LLM, policy, payment"]
  end
  PG[("PostgreSQL · Supabase")]
  ST[("Object storage · Supabase S3")]
  LLM["Groq LLM"]
  STUB["Stubbed policy admin and payment rails"]
  UI -- "REST + JWT" --> WEB --> DOM --> PORTS
  JOB --> PORTS
  PORTS --> PG & ST & LLM & STUB
  UI -. "presigned upload" .-> ST
```

## Modules

| Module | Responsibility | Since |
|---|---|---|
| `common` | error model, correlation ID, clock, OpenAPI | phase 1 |
| `identity` | users, roles, login, JWT, refresh tokens, current user | phase 1 |
| `claim` | FNOL, claim state machine, triage, assignment | phase 2 |
| `policy` | policy lookup (stub adapter) | phase 2 |
| `audit` | append-only audit trail, claim timeline | phase 2 |
| `platform` | job queue, timers, transactional outbox | phase 3 |
| `document` | storage port, presigned URLs, document lifecycle | phase 4 |
| `ai` | LLM port, extraction, damage assessment, fraud score | phase 5 |
| `exposure`, `payment`, `approval` | reserves, payments, authority limits, maker-checker | phase 6 |
| `siu`, `activity` | fraud investigations, tasks, SLA escalation | phase 7 |

Layers inside every module: `api` → `app` → `domain`, with `infra` for adapters and `config` for wiring.
Rules enforced by `ArchitectureTest` ([ADR-0002](adr/0002-modular-monolith-with-enforced-boundaries.md)).

## Request path (phase 1)

1. `CorrelationIdFilter` (first filter) accepts or creates `X-Correlation-Id` and puts it in the MDC.
2. Spring Security validates the bearer JWT (signature, expiry, issuer) and maps `role` to `ROLE_*`.
   Failures return a 401/403 `ApiError` from `SecurityErrorHandlers`.
3. The controller calls an application service; the service opens the transaction.
4. Exceptions become an `ApiError` in `GlobalExceptionHandler`.

## Key decisions

See the [ADR index](adr/README.md). The most important:
[database state machine instead of Camunda](adr/0006-database-state-machine-instead-of-a-workflow-engine.md),
[JWT with rotating refresh tokens](adr/0004-jwt-access-tokens-and-rotating-refresh-tokens.md) and
[free-tier hosting](adr/0007-free-tier-hosting.md).
