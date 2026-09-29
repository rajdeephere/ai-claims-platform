# Phase 6: Money and approvals

**Status:** ✅ Complete

## Goal

Claims are paid the way ClaimCenter does it: through exposures, reserves and payments. The system
enforces three rules:

- Nobody can pay more than was reserved.
- Nobody can go beyond their own authority.
- Nobody can approve their own request, and a timeout at the payment platform can never lead to paying
  twice.

## Delivered

| Item | Location |
|---|---|
| `exposure`, `approval_request`, `payment`, `recovery`, `payment_rail_stub` tables. The constraints: paid ≤ reserve, no self-approval, issued ⇔ reference, one pending approval per target | `V7__financials.sql` |
| Domain: `Money` (scale 2, never rounds), `Exposure`, `Payment`, `ApprovalRequest`, `Recovery` | `financials/domain/` |
| Authority limits read from the database on every check | `financials/app/AuthorityService` |
| Exposures, reserve changes (If-Match), close; payment requests (Idempotency-Key, row lock); denial requests; recoveries | `financials/app/FinancialsService` |
| Supervisor queue; approve / reject with maker-checker and authority checks, per kind (PAYMENT, RESERVE_CHANGE, DENIAL) | `financials/app/ApprovalService` |
| `ISSUE_PAYMENT` job: rail call outside any transaction, SIU hold, retry-safe | `financials/app/PaymentIssuing` |
| Payment rail port plus a stub with its own ledger. The stub's test payees: `REFUSE`, `FLAKY`, `LOST-RESPONSE` | `financials/domain/PaymentRail`, `financials/infra/StubPaymentRail` |
| `ClaimFinancialsPort` (claim domain), implemented by financials. It supplies the close and withdraw guards, the paid outcome, closes exposures on withdraw and denial, and the amount paid on claim views | `claim/domain/ClaimFinancialsPort`, `financials/app/ClaimFinancialsAdapter` |
| New claim actions `MANAGE_EXPOSURES`, `REQUEST_PAYMENT`, `REQUEST_DENIAL`, `RECORD_RECOVERY` | `claim/domain/ClaimAction` |
| The claimant is notified when a payment is issued | `notification/app/ClaimantNotifier` |
| ADRs 0024–0025; contract 1.5.0 (new paths; `amountPaid` on both claim views) | `docs/adr/`, `docs/openapi/api.v1.json` |

## Endpoints added

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/claims/{claimId}/exposures` | assigned adjuster, supervisor | create an exposure, with an optional initial reserve |
| GET | `/api/v1/claims/{claimId}/exposures` | staff | reserve, paid, committed, available |
| PUT | `/api/v1/exposures/{id}/reserve` | assigned adjuster, supervisor | set the reserve (If-Match); above your limit it waits for approval |
| POST | `/api/v1/exposures/{id}/close` | assigned adjuster, supervisor | close, releasing the unused reserve (If-Match) |
| POST | `/api/v1/exposures/{id}/payments` | assigned adjuster, supervisor | request a payment (Idempotency-Key) |
| GET | `/api/v1/claims/{claimId}/payments` | staff | payments and their status |
| POST | `/api/v1/claims/{claimId}/denial-requests` | assigned adjuster | propose a denial |
| POST / GET | `/api/v1/claims/{claimId}/recoveries` | assigned adjuster, supervisor / staff | subrogation, salvage |
| GET | `/api/v1/approvals?status=PENDING` | supervisor | the approval queue, oldest first |
| POST | `/api/v1/approvals/{id}/approve` · `/reject` | supervisor | decide (a rejection needs a reason) |

## Rules

| Situation | Result |
|---|---|
| Payment within your authority | `APPROVED` by you → `ISSUE_PAYMENT` job → `ISSUED` with the rail's reference |
| Payment above your authority | `PENDING_APPROVAL` + approval request; a supervisor approves → issued |
| Payment above the available amount (reserve − paid − committed) | 422 `INSUFFICIENT_RESERVE` |
| Raising a reserve above your authority | reserve unchanged + `RESERVE_CHANGE` approval request |
| Lowering a reserve below paid + committed | 422 `RESERVE_BELOW_COMMITTED` |
| Approving your own request | 422 `SELF_APPROVAL` |
| Approving above your own limit | 422 `AUTHORITY_EXCEEDED` |
| Deciding twice | 409 `ALREADY_DECIDED` |
| Second pending request for the same target | 409 `APPROVAL_PENDING` |
| Claim in `SIU_REVIEW` | payment request / approval 422 `SIU_HOLD`; issuing is held and rechecked every 30 min; reserves can still change |
| Rail refuses | payment `FAILED`, reserve available again |
| Rail times out (maybe paid) | job retries with the same key; payment stays `APPROVED` |
| Close claim with an open exposure / pending money | 422 `OPEN_EXPOSURES` / `PENDING_FINANCIALS` |
| Close claim after a payment was issued | outcome `PAID` (otherwise `NO_PAYMENT`) |
| Withdraw after a payment was issued | 422 `PAYMENT_ALREADY_ISSUED` |
| Denial approved | open exposures closed, claim CLOSED / DENIED by the approver |
| Recovery before any payment | 422 `NO_PAYMENT_TO_RECOVER` |

## Verification

| Check | Result |
|---|---|
| Unit tests | ✅ 174 (+ exposure, payment and approval domain rules; claim actions under SIU review) |
| Integration tests | ✅ 85 (+ financials 12) |
| Coverage (merged) | 94.1% lines, 78.5% branches; financials 92.6% lines |
| Payment within limit → ISSUED, exposure paid, claimant notified, exposure closed (unused reserve released), claim CLOSED / PAID, claimant sees PAID and the amount | ✅ |
| Reserve 15,000 and payment 12,000 by an adjuster → two approvals; adjuster 403; supervisor approves → ISSUED; timeline in order | ✅ |
| Supervisor approving own request → `SELF_APPROVAL`; approving 60,000 → `AUTHORITY_EXCEEDED` | ✅ |
| Rejection: reason required; payment REJECTED; reserve available again; second decision `ALREADY_DECIDED` | ✅ |
| Same Idempotency-Key → same payment (`Idempotent-Replayed: true`); different body → `IDEMPOTENCY_KEY_REUSED` | ✅ |
| Two parallel payments of 3,000 on a 4,000 reserve → one 201, one 422 | ✅ |
| SIU review → request and approval refused, payment stays PENDING_APPROVAL | ✅ |
| Rail refuses → FAILED; rail pays but the answer is lost → retried → ISSUED once, one ledger row, same reference | ✅ |
| Denial: proposed by the adjuster, a duplicate proposal 409, the adjuster can't approve it; approved by the supervisor → CLOSED / DENIED, exposures closed, claimant sees DENIED | ✅ |
| Recovery needs a payment; withdraw after payment refused | ✅ |
| Contract diff: only new paths and `amountPaid`; no existing path changed | ✅ |

## Issues found and fixed

Both were caught in review before the first test run.

1. **Repositories declared as nested interfaces (BUG-010).** Spring Data doesn't scan nested
   interfaces by default, so the context would have failed to start. Fixed by moving each repository to
   its own file.
2. **The pending-denial check could match another claim (BUG-011).** A denial targets the claim itself
   (`target_id` null). Looking it up as "kind DENIAL, target null" matched every claim's pending denial.
   Fixed with a lookup by claim id and kind.

## Deferred

- Payment stuck in `APPROVED` after the retries are used up → an activity for a person (phase 7).
- Recovery against a specific exposure; reversing a recovery or voiding a payment.
- Several currencies; deductible applied automatically; payments to several payees at once.
