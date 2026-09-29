# ADR-0026: SIU cases behind a port, with case-based visibility

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 7

## Context

The Special Investigations Unit (SIU) investigates suspected fraud. Four things matter:

- **Referral.** A claim reaches SIU in one of two ways. The triage rule refers it at intake (fraud score
  ≥ 70). Or a person refers an OPEN claim, usually after the "high fraud score" flag.
- **Visibility.** Investigators must see referred claims, and only those.
- **Secrecy.** The claimant must never learn about an investigation, because that tips them off.
- **Separation of duties.** SIU can't pay, and the system never denies a claim on its own.

The claim module owns the status change, but the case is its own record with its own lifecycle.

## Decision

- **Module and port.** A new `siu` module owns `siu_case`, with source RULE or MANUAL, the referrer,
  the fraud score at referral, and the status OPEN → CLEARED or CONFIRMED with findings. Two database
  rules back it up:
  - a partial unique index allows at most one open case per claim
  - check constraints require a referrer exactly when the referral is manual, and findings plus an
    investigator once decided

  The claim module defines `SiuReferralPort` (open a case; does the claim have one?), and siu implements
  it. The pattern is the same as `RiskAssessmentPort` and `ClaimFinancialsPort`.
- **Referral.** The case opens in the same transaction as the move to SIU_REVIEW:
  - intake: when triage refers
  - `POST /claims/{id}/refer-siu`: by the assigned adjuster or a supervisor, from OPEN only, with a
    reason

  The claim's version is flushed before the case insert. A lost race is then a clean 409 on the claim,
  not a unique-index error (BUG-003). A manual referral clears the `HIGH_FRAUD_SCORE` flag, because it
  has been acted on.
- **Visibility.** `ClaimAccess` lets the SIU role see a claim that has, or had, a case. After the outcome,
  investigators keep read access to what they investigated. For SIU, `allowedActions` is only `ADD_NOTE`.
- **Outcome.** `POST /siu/cases/{id}/outcome` is for the SIU role only. It needs findings and is decided
  once (`ALREADY_DECIDED`). In one transaction:
  - the case is decided
  - the claim goes SIU_REVIEW → OPEN, with the investigator as the actor of record
  - for **CONFIRMED**, a DENIAL approval request is proposed by the investigator

  A supervisor still decides the denial (maker-checker, ADR-0024). Payments held under the SIU hold
  resume after CLEARED.
- **Secrecy.** SIU events carry no claimant id, and the notifier doesn't subscribe to them (a unit test
  checks this). The claimant view shows SIU_REVIEW as IN_REVIEW, and SIU_REVIEW → OPEN sends no message.
- **No Spring bean cycle.** The adapter that implements the port uses only its repository, audit and
  outbox. `ClaimAccess` depends on the port, so an adapter that used claim services would form a cycle.
  The SIU events carry ids only; listeners look up the rest.

## Consequences

- ✅ An investigation is a record with an owner, findings and an audit trail, not just a status on the
  claim.
- ✅ Tested: SIU gets 404 on unreferred claims. SIU can't pay (403) or complete the investigation task by
  hand. Adjusters and supervisors can't record outcomes (403). No claimant notification mentions it.
- ⚠️ Every SIU investigator sees every referred claim. There is no case assignment, which is fine for a
  small unit; assigning cases to investigators would be the next step.
- ⚠️ A CONFIRMED outcome doesn't cancel payments that are waiting for approval. The supervisor must reject
  them before the denial can be approved (the denial approval refuses with `PENDING_FINANCIALS`).

## Alternatives considered

- **SIU as fields on the claim (a flag plus findings):** no history for re-referrals, and it grows the
  claim aggregate with another team's workflow.
- **The claim module calling the siu module directly:** siu depends on claim (it changes the claim's
  status), so this would be a module cycle.
- **An automatic denial on CONFIRMED:** the system never denies (design section 4, rule 4), and SIU
  recommends but doesn't decide.
