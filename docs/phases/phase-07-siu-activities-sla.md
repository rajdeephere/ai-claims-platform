# Phase 7: SIU, information requests, activities and SLAs

**Status:** ✅ Complete

## Goal

Suspected fraud goes to investigators who can see only what was referred to them, while no money goes out
and the claimant notices nothing. Unanswered questions have deadlines. Every piece of follow-up work is a
task with an owner and an SLA. When a task isn't done in time, supervisors see it.

## Delivered

| Item | Location |
|---|---|
| `siu_case`, `activity` tables; info requests can expire | `V8__siu_and_activities.sql` |
| SIU cases: RULE / MANUAL referral, one open per claim, decided once with findings | `siu/domain/SiuCase` |
| `SiuReferralPort` (claim domain), implemented by siu, which opens the case in the referral's transaction | `claim/domain/SiuReferralPort`, `siu/app/SiuReferralAdapter` |
| SIU sees claims with a case; manual referral; triage referral opens a case | `claim/app/ClaimAccess`, `ClaimCommandService.referToSiu`, `ClaimIntakeService` |
| Outcome: CLEARED → OPEN; CONFIRMED → OPEN + a denial proposed by the investigator | `siu/app/SiuCaseService`, `FinancialsService.proposeDenial` |
| Case file for investigators: the case, the claim, other claims on the policy | `SiuCaseService.caseFile` |
| Information-request timers: reminder 3 d, overdue 7 d, expiry 14 d; adjuster can cancel a request | `claim/app/InfoRequestTimers`, `ClaimCommandService.cancelInformationRequest` |
| Activities: owned by a person or a role queue, SLA timer, escalation once | `activity/domain/Activity`, `ActivityType`, `activity/app/ActivityService` |
| Activity planner: tasks opened, moved and closed from outbox events | `activity/app/ActivityPlanner` |
| Stuck payment: `PAYMENT_STUCK` on the last failed attempt (payment stays APPROVED); `PAYMENT_FAILED` event | `financials/app/PaymentIssuing` |
| New claim events (`CLAIM_ASSIGNED`, `HIGH_FRAUD_SCORE`, info-request timers); payloads carry the assigned adjuster | `claim/domain/ClaimEvents`, `ClaimAuditTrail` |
| Claimant reminder and "we didn't hear back" notifications | `notification/app/ClaimantNotifier` |
| Supervisor dashboard | `activity/app/SupervisorDashboardService` |
| Money in event payloads as strings (BUG-012) | `PaymentIssuing`, ADR-0028 |
| ADRs 0026–0028; contract 1.6.0 | `docs/adr/` |

## Endpoints added

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/claims/{id}/refer-siu` | assigned adjuster, supervisor | OPEN → SIU_REVIEW with a reason (If-Match) |
| POST | `/api/v1/claims/{id}/cancel-info-request` | assigned adjuster | AWAITING_INFO → OPEN; timers cancelled (If-Match) |
| GET | `/api/v1/siu/cases?status=OPEN` | SIU, supervisor | SIU queue, oldest referral first |
| GET | `/api/v1/siu/cases/{id}` | SIU, supervisor | case file |
| POST | `/api/v1/siu/cases/{id}/outcome` | SIU | CLEARED / CONFIRMED with findings |
| GET | `/api/v1/claims/{claimId}/siu-cases` | staff who can see the claim | the claim's cases |
| GET | `/api/v1/activities?overdueOnly=` | staff | my open tasks: mine and my role's queue, soonest due first |
| GET | `/api/v1/activities/breached` | supervisor | every open task past its SLA |
| POST | `/api/v1/activities/{id}/complete` | owner, supervisor | complete with an optional note |
| GET | `/api/v1/claims/{claimId}/activities` | staff who can see the claim | all tasks on the claim |
| GET | `/api/v1/dashboard/supervisor` | supervisor | claims by status, unassigned, pending approvals, open SIU cases, open and breached tasks, breaches per owner |

`allowedActions` gained `REFER_TO_SIU` and `CANCEL_INFO_REQUEST`. Clients must ignore action values they
don't know (ADR-0008).

## Activity types

| Type | Opened when | Owner | SLA | Closed by |
|---|---|---|---|---|
| FIRST_CONTACT | the claim is first assigned | adjuster | 24 h (fast-track 4 h) | the adjuster |
| CONSIDER_SIU_REFERRAL | the score crosses 70 on an open claim | adjuster | 1 d | the adjuster, or automatically on referral |
| INFO_OVERDUE | 7 days without an answer | supervisors | 1 d | a supervisor; cancelled when the claim leaves AWAITING_INFO |
| INFO_NO_RESPONSE | 14 days without an answer (request expired) | adjuster | 2 d | the adjuster |
| SIU_INVESTIGATION | an SIU case opens | SIU | 5 d | recording the outcome only |
| PAYMENT_STATUS_UNKNOWN | the payment rail was unreachable on every attempt | supervisors | 1 d, URGENT | a supervisor, or automatically when the payment is issued or refused |
| REVIEW_REOPENED | a supervisor reopens the claim | adjuster | 2 d | the adjuster |

Adjuster-owned work goes to the supervisors' queue while nobody is assigned, and follows the claim on
reassignment. Closing the claim cancels everything open.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `app.info-requests.remind-after` | 3d | reminder to the claimant |
| `app.info-requests.overdue-after` | 7d | follow-up task for the supervisors |
| `app.info-requests.expire-after` | 14d | the request expires; the claim is back with the adjuster |

## Verification

| Check | Result |
|---|---|
| Unit tests | ✅ 188 (+ activity and SIU case rules, info-request expiry and cancel, SIU flag cleared on referral, notifier: reminders and SIU secrecy) |
| Integration tests | ✅ 98 (+ SIU 3, info-request timers 4, activities 5, one more financials test; the AI pipeline tests extended) |
| Coverage (merged) | 95.2% lines, 79.9% branches; siu 97.7%, activity 99.0% lines |
| Manual referral → SIU_REVIEW, MANUAL case, SIU can now see the claim (404 before), only `ADD_NOTE` for SIU, investigation task in the SIU queue, claimant still IN_REVIEW | ✅ |
| Under review: payment 422 SIU_HOLD; SIU paying 403; second referral 409; adjuster recording an outcome 403; SIU completing the investigation task by hand 422 | ✅ |
| CLEARED → OPEN, payment possible, task completed, SIU keeps read access, second outcome 409; no claimant notification mentions SIU or fraud | ✅ |
| CONFIRMED → OPEN + denial by siu1; adjuster's own denial 409; supervisor approves → CLOSED / DENIED | ✅ |
| Triage referral at intake (score 80) → RULE case with score, no referrer | ✅ |
| Score crossing 70 on an open claim → "consider SIU referral" task for the adjuster | ✅ |
| Info request: 3 timers 11 days apart; reminder notification; supervisor follow-up; expiry → OPEN by system, request EXPIRED, adjuster task, follow-up cancelled, claimant told | ✅ |
| Answer, adjuster cancel (other adjuster 404, supervisor 403) and withdrawal all cancel the timers | ✅ |
| First contact 24 h (fast-track 4 h); SLA timer → URGENT, `SLA_BREACHED` in the timeline, breached list, dashboard per owner; completion by others 404, twice 409; completing on time cancels the timer | ✅ |
| Reassign → the task moves (not duplicated); close → cancelled; reopen → review task for the new adjuster | ✅ |
| Rail down on every attempt → job FAILED, payment still APPROVED, URGENT supervisor task "Rs 1000.00 to DOWN Garage" | ✅ |
| Contract diff: only new paths and new `allowedActions` values | ✅ |

## Issues found and fixed

1. **"Rs 3800.0" in the claimant's payment notification (BUG-012).** The bug was in phase 6. A jsonb
   payload read back as a Map turns 3800.00 into the double 3800.0. The phase 6 test checked only the
   subject line. Money in event payloads is now a scale-2 string (ADR-0028), and the test checks the whole
   body.
2. **A flaky financials test: 412 instead of 422 (BUG-013).** The test read the exposure's ETag while the
   background issuing job was about to update the exposure. The server was right: the adjuster's view was
   stale. The test now waits for issuing, and a new test states the behaviour: a stale ETag after a
   background payment gets 412.
3. **An exact-order timeline assertion broke.** Activity entries, written by a listener a moment later,
   now interleave with the claim's own events. `ClaimLifecycleIT` checks the claim's own events and
   ignores activity entries.

## Deferred

- Assigning SIU cases to investigators; business-hours SLA calendar; pausing the first-contact SLA while
  waiting for the claimant.
- SLA lengths in configuration; escalation to a named supervisor instead of the queue.
- A CONFIRMED outcome that also rejects waiting payment approvals.
