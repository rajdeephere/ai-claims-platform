# Build Plan

The full design is in [design/design-document-v1.md](design/design-document-v1.md) (also as [PDF](design/design-document-v1.pdf)).
The system is built in eight phases, backend first. Each phase ends with passing tests, updated docs and
ADRs, and a commit made by the developer.

| Phase | Scope | Main deliverables | Status |
|---|---|---|---|
| 1 | Foundation | Maven project (Java 17, Spring Boot 3.5), Postgres + Flyway, JWT login with 4 roles and refresh-token rotation, error model, correlation ID, OpenAPI contract check, ArchUnit rules, Dockerfile, CI | ✅ |
| 2 | Claim core | Policy stub, FNOL with idempotency key, claim state machine, claim number, claimant vs staff views, visibility rules, audit trail, timeline, ETag / If-Match | ⏳ |
| 3 | Jobs and timers | Database job queue (`SKIP LOCKED`, leases, backoff), transactional outbox + relay, policy verification job, assessment timeout | ⏳ |
| 4 | Documents | Storage port, MinIO locally, Supabase S3 in the cloud, presigned upload/download, content-type detection, SHA-256 de-duplication | ⏳ |
| 5 | AI pipeline | LLM port, Groq client + stub, extraction and damage assessment, output validation, fraud score, triage and assignment | ⏳ |
| 6 | Money and approvals | Exposures, reserves, payments, authority limits, maker-checker approvals, payment port with idempotency, denial requests | ⏳ |
| 7 | SIU, information requests, SLA | SIU cases and payment hold, request-info timers, activities and escalation, recovery, close and reopen | ⏳ |
| 8 | UI and deployment | Angular portal and staff workspace (theme supplied), Vercel + Render + Supabase deployment, keep-alive | ⏳ |

## Definition of done (every phase)

- [ ] Unit and integration tests pass (`mvn verify`)
- [ ] OpenAPI contract regenerated if the API changed, and the diff reviewed
- [ ] `docs/phases/phase-NN-*.md` written; new ADRs added to `docs/adr/`
- [ ] Private notes updated in `interview-prep/` (gitignored): study notes and the bug-fix log
- [ ] Changes reviewed and committed by the developer

## Local ports

| Service | Port | Why not the default |
|---|---|---|
| API | 8081 | 8080 is used by a local Apache; 8000 by ClaimFlow's gateway |
| PostgreSQL | 5434 | 5432 is a local Postgres install, 5433 is ClaimFlow |
| MinIO (phase 4) | 9000 / 9001 | |
| Angular (phase 8) | 4200 | |
