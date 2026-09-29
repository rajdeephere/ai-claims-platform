# ADR-0018: Claim intake runs as a chain of jobs

- **Status:** Accepted (supersedes [ADR-0014](0014-synchronous-intake-until-the-job-queue.md))
- **Date:** 2026-09-29
- **Phase:** 3

## Context

Phase 2 ran policy check, triage and assignment inside the FNOL transaction. That was acceptable only
because the policy "system" was a local stub. A real policy system is remote: calling it inside the FNOL
transaction would hold a database connection during a network call and fail the claimant's FNOL whenever
the policy system is slow or down. Phase 5 adds an AI assessment that takes seconds to minutes.

## Decision

FNOL records the claim (SUBMITTED) and schedules the first job in the same transaction; the response
returns at once with status RECEIVED. The chain:

| Job | Transactional | Does | Schedules |
|---|---|---|---|
| `VERIFY_POLICY` | no | reads the claim, calls the policy port **outside a transaction**, then in a short transaction: policy check, SUBMITTED → ASSESSING | `COMPLETE_ASSESSMENT` now, `ASSESSMENT_TIMEOUT` in 10 min |
| `COMPLETE_ASSESSMENT` | yes | triage, ASSESSING → OPEN or SIU_REVIEW, assignment; cancels the timeout | — |
| `ASSESSMENT_TIMEOUT` | yes | if still ASSESSING: flag `ASSESSMENT_TIMED_OUT` and complete anyway | — |

- Each step re-reads the claim and returns if it has already moved on, so a repeated run is harmless.
- Each step schedules the next in its own transaction: the chain can't break between steps.
- The timeout is a safety net for phase 5: a claim is never stuck because the AI didn't answer.
- Correlation: every audit entry of the chain carries the FNOL request's correlation ID (tested).

## Consequences

- ✅ FNOL is fast and never fails because of the policy system.
- ✅ A policy-system outage just delays intake; jobs retry with backoff.
- ⚠️ The claimant briefly sees RECEIVED before IN_REVIEW; the UI polls (phase 8).
- ⚠️ Tests must wait for background work (Awaitility), never sleep a fixed time.

## Alternatives considered

- **Keep the synchronous intake:** see Context.
- **One job doing all steps:** a failure in assignment would redo the policy call; separate steps retry
  independently.
