# ADR-0014: Synchronous claim intake in phase 2, moved to jobs in phase 3

- **Status:** Accepted (temporary; superseded in phase 3)
- **Date:** 2026-09-29
- **Phase:** 2

## Context

After FNOL, the system checks the policy, triages the claim and assigns an adjuster
(SUBMITTED → ASSESSING → OPEN). In the target design these are background jobs: the policy system is
remote, and the AI assessment (phase 5) takes seconds to minutes. The job queue arrives in phase 3.

## Decision

In phase 2, `ClaimIntakeService.process` runs inside the FNOL transaction. That's acceptable **only**
because every step is local: the policy "system" is a stub reading our own tables, triage is pure code
and assignment is a query. Everything commits together, and a failure rolls the whole FNOL back.

The service is written as it will run later: it takes a claim, uses only the policy port and the domain
rules, and requires an existing transaction. Phase 3 calls it from job handlers instead, one step per job.

## Consequences

- ✅ The full lifecycle is usable and testable end to end now.
- ✅ The claim passes through ASSESSING with audited transitions, so the lifecycle and timeline already
  have their final shape.
- ⚠️ A real, remote policy system must never be called inside this transaction (slow calls hold
  connections; failures lose the FNOL). Phase 3 removes that risk.

## Alternatives considered

- **`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`:** the pattern that loses work in the
  Zynteq review: a crash after commit means the step never runs and nothing retries it.
- **Wait for phase 3 before any lifecycle:** delays all claim handling and its tests.
