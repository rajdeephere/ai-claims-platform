# Architecture Decision Records

| ADR | Decision | Status | Phase |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted | 1 |
| [0002](0002-modular-monolith-with-enforced-boundaries.md) | Modular monolith with boundaries enforced by ArchUnit | Accepted | 1 |
| [0003](0003-flyway-owns-the-schema.md) | Flyway owns the schema; demo data in a separate location | Accepted | 1 |
| [0004](0004-jwt-access-tokens-and-rotating-refresh-tokens.md) | JWT access tokens with rotating, reuse-detecting refresh tokens | Accepted | 1 |
| [0005](0005-authority-limits-read-from-the-database.md) | Authority limits read from the database, not the token | Accepted | 1 |
| [0006](0006-database-state-machine-instead-of-a-workflow-engine.md) | Database state machine and job queue instead of Camunda | Accepted | 1 (built in 2–3) |
| [0007](0007-free-tier-hosting.md) | Free-tier hosting: Vercel, Render, Supabase, Groq | Accepted | 1 |
| [0008](0008-api-versioning-and-committed-openapi-contract.md) | URI versioning and a committed, build-checked OpenAPI contract | Accepted | 1 |
| [0009](0009-one-error-model-with-stable-codes.md) | One error model with stable error codes | Accepted | 1 |
| [0010](0010-testing-strategy.md) | Testing strategy: unit, ArchUnit, Testcontainers, contract | Accepted | 1 |
| [0011](0011-idempotent-fnol-with-idempotency-keys.md) | Idempotent FNOL with per-user Idempotency-Key | Accepted | 2 |
| [0012](0012-separate-claimant-and-staff-apis-with-etag-if-match.md) | Separate claimant and staff APIs; ETag / If-Match on every command | Accepted | 2 |
| [0013](0013-append-only-audit-trail.md) | Append-only audit trail, written in the business transaction | Accepted | 2 |
| [0014](0014-synchronous-intake-until-the-job-queue.md) | Synchronous intake in phase 2, moved to jobs in phase 3 | Superseded by 0018 | 2 |
| [0015](0015-claim-visibility-permission-and-status-checks.md) | Claim access: visibility (404), permission (403), status (409) | Accepted | 2 |
| [0016](0016-database-job-queue.md) | Job queue in PostgreSQL: SKIP LOCKED, leases, backoff, timers | Accepted | 3 |
| [0017](0017-transactional-outbox-with-in-process-relay.md) | Transactional outbox with an in-process relay | Accepted | 3 |
| [0018](0018-claim-intake-as-jobs.md) | Claim intake as a chain of jobs | Accepted | 3 |
| [0019](0019-documents-presigned-upload-and-verification.md) | Documents: presigned direct upload, verified on completion; SeaweedFS / Supabase Storage | Accepted | 4 |
| [0020](0020-stable-explicit-operation-ids.md) | Explicit, stable operationIds on every endpoint | Accepted | 4 |

New ADRs use [template.md](template.md).
