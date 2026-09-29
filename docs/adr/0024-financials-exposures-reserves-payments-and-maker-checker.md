# ADR-0024: Financials: exposures, reserves, payments and maker-checker approvals

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 6

## Context

Money is where a claims system gets audited. Three rules matter:

- Nobody pays more than was reserved.
- Nobody moves money beyond their own authority.
- Nobody approves their own request.

Payments also leave the system through a payment platform that can time out after it has already paid.
ClaimCenter's model is claim → exposures (one per coverage and claimant) → reserves and payments against
each exposure, and this ADR follows it.

## Decision

- **Model.** A new `financials` module owns four tables:
  - `exposure`: type, coverage, claimant, reserve, paid
  - `payment`
  - `approval_request`
  - `recovery`

  The claim module sees money only through `ClaimFinancialsPort`, which it owns and financials
  implements. The port answers two questions: "Can this claim close or be withdrawn?", and it can close
  the exposures of a withdrawn or denied claim.
- **Available money.** `reserve − paid − committed`, where committed means payments that are
  `PENDING_APPROVAL` or `APPROVED` but not yet issued. A payment request above what's available fails
  with `INSUFFICIENT_RESERVE`.
- **Integrity in the database, not only in code.** Check constraints enforce three things:
  - `paid ≤ reserve`
  - `decided_by ≠ requested_by`
  - issued ⇔ has an external reference

  A partial unique index allows only one pending approval per target.
- **Authority.** Each user's limit comes from `app_user.authority_limit` on every check, never from the
  JWT (ADR-0005). The demo limits: adjusters 5,000; supervisors 50,000.
  - **Payments.** Within your limit, the payment is approved by you and issued. Above it, the payment is
    `PENDING_APPROVAL` and an approval request goes to the supervisors.
  - **Reserves.** Raising a reserve above your limit also creates an approval request. Lowering is always
    allowed, but never below paid + committed.
- **Maker-checker.**
  - The approver must not be the requester (`SELF_APPROVAL`).
  - The approver's own limit must cover the amount (`AUTHORITY_EXCEEDED`).
  - A request is decided exactly once (`ALREADY_DECIDED`).
  - Rejecting needs a reason.
  - Every denial goes through a supervisor. The adjuster proposes it; the claim becomes CLOSED/DENIED
    only on approval, in the approver's transaction.
- **SIU hold.** While the claim is in `SIU_REVIEW`, three things are blocked: requesting a payment,
  approving one, and issuing one. The issuing job reschedules itself for 30 minutes later. Reserves can
  still change, because an investigation often raises them.
- **Payment rail.** A `PaymentRail` port handles the call.
  - An `ISSUE_PAYMENT` job calls it outside any transaction, sending the payment's own idempotency key.
  - **Refused:** the payment becomes `FAILED`, which frees the reserve.
  - **Unavailable:** the job retries with backoff and **the payment stays `APPROVED`**, because the money
    may already have left.
  - **Success:** in one transaction, the payment becomes `ISSUED`, its amount is added to the
    exposure's paid, and the audit entry and a `PAYMENT_ISSUED` outbox event are written.

  The stub rail keeps its own ledger table, one row per key, so tests can prove "paid once".
- **Payment requests are idempotent** (`Idempotency-Key`, ADR-0011). A retried request returns the
  first payment.

## Consequences

- ✅ Overspending, self-approval and double payment are each blocked twice: by code and by the database.
- ✅ An unknown rail outcome is handled safely: retrying with the same key can't pay twice, and marking
  the payment failed can't lead to paying again by hand.
- ✅ The claim module stays free of money logic; ArchUnit still sees no cycles.
- ⚠️ A payment stuck in `APPROVED` (the rail is down for longer than the retries) ends as a FAILED job in
  the ops API. Someone must check with the bank before doing anything. Phase 7 turns this into an
  activity.
- ⚠️ No currency handling: everything is INR at scale 2, and amounts with more decimals are refused, not
  rounded.

## Alternatives considered

- **Reserves on the claim itself (no exposures):** simpler, but it can't pay a garage and a hospital under
  different coverages, and it isn't how Guidewire models it.
- **Authority limit in the JWT:** it goes stale for up to 15 minutes after a limit changes. Rejected in
  ADR-0005.
- **Calling the rail inside the approval transaction:** it holds a lock during a network call, and a
  rollback after the bank paid loses the record of the payment.
- **Marking the payment FAILED on a timeout:** that invites a second payment for money that may already
  have gone out.
