# ADR-0016: A job queue in PostgreSQL for background work and timers

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 3

## Context

Claims need background work (policy check, AI assessment, notifications) and timers (assessment timeout,
reminders, SLA escalation, all days apart). The API runs on Render's free tier, which puts the container
to sleep after 15 minutes idle, and it may restart at any time. In-memory schedulers (`@Scheduled` with
state, `@Async`, Quartz RAM store) lose work on restart. There is no free hosted message broker.

## Decision

A `job` table used as a queue, driven by `JobRunner`:

| Concern | How |
|---|---|
| Scheduling | `JobService.schedule` (MANDATORY propagation): the job commits with the change that needs it |
| Timers | a job with a future `due_at`; cancelling = status CANCELLED |
| Duplicates | `dedup_key` with a partial unique index; `INSERT ... ON CONFLICT DO NOTHING` |
| Pickup | `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id`: workers never wait for or double-claim each other |
| Crashes | a lease (`locked_until`, 5 min); an expired RUNNING job is picked again |
| Retries | the attempt is counted at pickup; failure → PENDING with backoff 30 s, 2 min, 10 min, 1 h; after 5 attempts → FAILED |
| Permanent errors | `PermanentJobFailure` → FAILED at once |
| Exactly-once for DB work | transactional handlers: the work and `DONE` commit in one transaction; `DONE` only if we still hold the lease, otherwise the work rolls back |
| Remote calls | non-transactional handlers call the remote system outside any transaction, then apply the result in a short one |
| Tracing | the job stores the scheduling request's correlation ID and puts it in the MDC while running |
| Operations | `GET /api/v1/ops/jobs?status=FAILED`, `POST /ops/jobs/{id}/retry` (SUPERVISOR) |
| Housekeeping | DONE/CANCELLED jobs deleted after 7 days; FAILED kept |

Handlers must be idempotent: they re-read state and stop if the work is already done.

## Consequences

- ✅ Survives restarts, crashes and the free tier's sleep: all state is in the database.
- ✅ Scales to several instances with no coordinator (SKIP LOCKED).
- ✅ One technology to run and back up (PostgreSQL).
- ⚠️ Polling adds up to `poll-interval` (2 s) latency and a small constant query load.
- ⚠️ Throughput is fine for claims (hundreds per minute), not for millions of messages; that's what a broker is for.

## Alternatives considered

- **Camunda / Zeebe timers and jobs:** see ADR-0006.
- **Quartz with a JDBC store:** also durable, but its own tables and scheduler model are heavier than
  needed, and it doesn't commit with our business transaction.
- **Kafka / RabbitMQ with delayed messages:** no free hosting; delayed delivery needs plugins or extra topics.
- **db-scheduler library:** close to this design; writing it made each guarantee explicit and testable.
