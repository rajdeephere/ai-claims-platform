# ADR-0027: Activities created from events, with SLA timers; information-request deadlines

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 7

## Context

Claims work is driven by tasks with deadlines. In ClaimCenter these are "activities":

- contact the claimant within a day
- follow up on an unanswered question
- investigate a referral
- find out what happened to a payment the bank never confirmed

Missing a deadline must be visible to supervisors. Tasks come from many modules (claim, siu,
financials), but none of those should depend on a task module. An information request also has
deadlines of its own: a reminder, an escalation, and a final expiry.

## Decision

- **An `activity` module owns the tasks.** Each activity is owned either by a person (`assignee_id`) or
  by a role queue (`candidate_role`), never both (check constraint). It has a type, a due time and a
  priority, and it may link to the record it's about (info request, SIU case, payment).
  - Status goes OPEN → COMPLETED or CANCELLED.
  - A partial unique index on `(claim, type, coalesce(linked_id, 0))` allows each task to be open at most
    once.
- **Created only from outbox events.** `ActivityPlanner` is an outbox listener (at most once per event,
  in its own transaction). It opens, moves and closes activities in response to events the other modules
  already publish:

  | Event | What happens to activities |
  |---|---|
  | `CLAIM_ASSIGNED` | first contact is opened (4 h for fast-track, else 24 h); the adjuster's open work moves to the new adjuster |
  | `HIGH_FRAUD_SCORE` | "consider an SIU referral" is opened |
  | `INFO_REQUEST_OVERDUE` | a follow-up is opened in the supervisors' queue |
  | `INFO_REQUEST_EXPIRED` | "decide how to proceed" is opened for the adjuster |
  | `SIU_CASE_OPENED` / `SIU_CASE_DECIDED` | the investigation is opened in the SIU queue / completed |
  | `PAYMENT_STUCK` | "status unknown" is opened for the supervisors; `PAYMENT_ISSUED` or `PAYMENT_FAILED` completes it |
  | status change to CLOSED | everything open is cancelled |
  | status change CLOSED → OPEN | "review the reopened claim" is opened |

  No activity is opened on a claim that is already closed, in case an event is processed late.
- **SLA.** Each activity schedules one `ACTIVITY_DUE` job at its due time (dedup key per activity).
  Completing the activity cancels the job. If the job fires while the activity is still open, the activity
  is escalated once:
  - priority becomes URGENT
  - `escalated_at` is set
  - `SLA_BREACHED` goes on the claim's audit trail

  Supervisors see breaches at `GET /activities/breached` and per owner on
  `GET /dashboard/supervisor`.
- **Completing.** The owner or a supervisor can complete an activity, in the usual order: visible (404),
  mine (403), completable (422), open (409). The SIU investigation is completed only by recording the
  outcome (`COMPLETES_AUTOMATICALLY`).
- **Information-request deadlines are three timers in the job queue**, all scheduled with the request:

  | After (configurable) | What happens |
  |---|---|
  | 3 days | reminder to the claimant |
  | 7 days | `INFO_REQUEST_OVERDUE`, a follow-up task for the supervisors |
  | 14 days | the request expires and the claim returns to OPEN (actor: system); the adjuster gets a task |

  An answer, the adjuster's cancel (`POST /claims/{id}/cancel-info-request`) or a withdrawal cancels the
  timers. Each timer re-reads the request and does nothing if it's no longer open.
- **Stuck payments.** When the rail is still unreachable on the payment job's last attempt,
  `PAYMENT_STUCK` is published. The payment stays APPROVED (ADR-0024). Retrying the job from the ops API
  reuses the idempotency key.

## Consequences

- ✅ The claim, siu and financials modules don't know that activities exist. Adding a task type is one
  enum value and one line in the planner.
- ✅ Timers, retries and SLA checks all live in the one job queue: no scheduler state outside the
  database, and nothing is lost when the free-tier instance sleeps.
- ⚠️ Activities appear a moment after the change that caused them, one relay poll later (asynchronous).
  Tests wait for them, and a test that asserted the exact timeline order had to learn to ignore
  activity entries.
- ⚠️ SLAs are wall-clock hours. There is no business-hours calendar, and the SLA doesn't pause while
  waiting for the claimant.
- ⚠️ The SLA lengths are constants in `ActivityType`. The info-request deadlines are configuration.

## Alternatives considered

- **Each module calls an ActivityService directly:** synchronous and simpler to test, but claim → activity
  → claim is a cycle, and every module would need to know about tasks.
- **A scheduler scanning for overdue activities every minute:** one query instead of one job per activity,
  but it adds a second timer mechanism beside the job queue, plus its own "already escalated" bookkeeping.
- **A workflow engine's user tasks and timers (Camunda):** rejected in ADR-0006.
