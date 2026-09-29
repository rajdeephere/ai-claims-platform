# Phase 3: Jobs, timers and the outbox

**Status:** ✅ Complete

## Goal

Background work and timers that survive restarts, crashes and Render's sleep, with nothing extra to
host; events that are never lost and never announce a change that rolled back.

## Delivered

| Item | Location |
|---|---|
| Job queue: `job` table, `SKIP LOCKED` pickup, leases, backoff (30 s → 1 h), dead jobs, dedup keys, timers | `platform/jobs/`, `V4__jobs_outbox_notifications.sql` |
| Transactional vs remote-call handlers; DONE only while holding the lease | `JobHandler`, `JobRunner` |
| Correlation ID carried from the scheduling request into the job's logs and audit entries | `JobRunner`, `job.correlation_id` |
| Ops API: failed jobs, retry (SUPERVISOR) | `platform/jobs/api/JobOpsController` |
| Transactional outbox, relay with per-listener transactions and `processed_event` | `platform/outbox/` |
| Claim events: `CLAIM_SUBMITTED`, `CLAIM_STATUS_CHANGED`, `INFO_REQUESTED` (self-contained payloads) | `claim/domain/ClaimEvents`, `ClaimAuditTrail` |
| Intake as jobs: `VERIFY_POLICY` (policy call outside any transaction) → `COMPLETE_ASSESSMENT`, `ASSESSMENT_TIMEOUT` timer | `claim/app/intake/` |
| In-app claimant notifications (never about SIU) + portal endpoints | `notification/` |
| Housekeeping: idempotency records > 24 h, finished jobs and published events > 7 days | `platform/housekeeping/` |
| ADRs 0016–0018 (0014 superseded); contract 1.2.0 | `docs/adr/`, `docs/openapi/api.v1.json` |

## Endpoints added

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/portal/notifications` | CLAIMANT | my notifications, newest first |
| GET | `/api/v1/portal/notifications/unread-count` | CLAIMANT | badge count |
| POST | `/api/v1/portal/notifications/{id}/read` | CLAIMANT | mark read (idempotent) |
| GET | `/api/v1/ops/jobs?status=FAILED` | SUPERVISOR | background jobs by status |
| POST | `/api/v1/ops/jobs/{id}/retry` | SUPERVISOR | retry a FAILED job |

**Behaviour change:** FNOL now returns `RECEIVED` (SUBMITTED) immediately; the claim becomes `IN_REVIEW`
(OPEN) a moment later, when the intake jobs have run. The staff responses gained the flag value
`ASSESSMENT_TIMED_OUT` (an added enum value in a response: non-breaking for clients that tolerate
unknown values, as ours will).

## Configuration (`app.*`)

| Key | Default | Meaning |
|---|---|---|
| `jobs.poll-interval-ms` | 2000 | how often the runner looks for due jobs |
| `jobs.batch-size` | 5 | jobs per pass |
| `jobs.lease` | 5m | after this a RUNNING job may be taken over |
| `jobs.max-attempts` / `jobs.backoff` | 5 / 30s, 2m, 10m, 1h | retry policy |
| `outbox.poll-interval-ms` / `retry-delay` / `max-attempts` | 2000 / 1m / 10 | relay |
| `intake.assessment-timeout` | 10m | the assessment's safety-net timer |
| `housekeeping.cron` | `0 17 * * * *` | hourly clean-up |

## Verification

| Check | Result |
|---|---|
| Unit tests | ✅ 120 (+ retry policy, outbox failure, notification messages incl. SIU silence) |
| Integration tests | ✅ 57 (+ job queue 8, intake jobs 2, outbox 4, housekeeping 1) |
| Coverage (merged) | 95.7% lines, 85.3% branches |
| Job retried with backoff until success; permanent failure → FAILED; ops retry | ✅ |
| Worker "crash" (expired lease) → job taken over; last attempt → FAILED | ✅ |
| Two workers polling at once → disjoint jobs | ✅ |
| Event appended in a rolled-back transaction never exists | ✅ |
| Flaky listener retried, successful listener not called again | ✅ |
| Timeout timer completes a stuck assessment and flags it | ✅ |
| FNOL correlation ID on every audit entry of the intake chain | ✅ |
| Local smoke test: FNOL `RECEIVED`, OPEN and assigned within 5 s, two notifications delivered | ✅ |
| Existing database upgraded in place (V4) | ✅ |

## Issues found and fixed

1. **Outbox relay would hang forever with `FOR UPDATE` (BUG-004).** The relay locks the event row and
   runs each listener in its own transaction; the listener's `processed_event` insert needs a KEY SHARE
   lock on that row for its foreign key, which `FOR UPDATE` blocks. Spotted while writing the relay,
   then proven: with `FOR UPDATE`, 3 of 4 outbox tests hang to their timeout; with `FOR NO KEY UPDATE`
   all pass.

## Deferred

- AI assessment jobs between the policy check and triage (phase 5).
- Reminder, escalation and SLA timers (phase 7), built on the same queue.
- E-mail delivery (a second listener behind a port); in-app only in v1.
