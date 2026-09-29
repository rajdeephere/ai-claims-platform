# ADR-0022: An explainable fraud score, behind a port the claim module owns

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 5

## Context

Triage needs a fraud score, and the score needs AI results. But the ai module already depends on the
document module, which depends on the claim module: if claim depended on ai, the modules would form a
cycle (claim → ai → document → claim), which ArchUnit forbids.

A fraud score also has to be explainable: SIU investigators and adjusters must see *why* a claim scored
high, and a regulator may ask.

## Decision

**Dependency inversion:** the claim domain defines `RiskAssessmentPort`
(`assessmentPending(claimId)`, `assess(facts)`); the ai module implements it (`ClaimRiskAssessor`).
The claim module never imports anything from ai.

**Scoring (0–100, capped), mostly deterministic rules:**

| Signal | Source | Points |
|---|---|---|
| Loss within 30 days of policy start | rule (policy start captured at the policy check) | 25 |
| ≥ 2 other claims on the policy in the 12 months before the loss | rule | 20 |
| A document identical (SHA-256) to one on another claim | rule | 30 |
| Estimate or invoice dated before the loss | rule on the AI extraction | 15 |
| Amount claimed > 1.5 × the highest assessed damage | rule on the AI extraction | 15 |
| Document looks edited | LLM signal | 15 |
| Document contradicts the claimant's description | LLM signal | 20 |
| Document contains instructions to the AI | LLM signal | 10 |

Each LLM signal kind counts once per claim. Every point is stored with a reason (the FRAUD_SCORE
assessment and the audit entry). Scores use each document's **effective** result: a person's override
replaces what the model read.

**Intake:** triage waits while documents are still being assessed (re-checking every 15 s after a 2-minute
grace period for the FNOL form's uploads), bounded by the 10-minute timeout. A score ≥ 70 at intake routes
the claim to SIU_REVIEW. A later document that pushes an open claim over 70 only adds the
`HIGH_FRAUD_SCORE` flag: moving a claim someone is already handling into SIU is a person's decision (SIU
referral, phase 7).

## Consequences

- ✅ No module cycle; claim intake is testable with any port implementation.
- ✅ Every score is explainable line by line; weights are in one place and unit-tested at their boundaries.
- ⚠️ Weights are expert guesses, not trained; they belong in configuration once real outcomes exist.
- ⚠️ Documents uploaded after triage don't change the segment, only the score and flags.

## Alternatives considered

- **Claim calls the ai module directly:** a module cycle.
- **An ML model trained on past fraud:** no labelled history in a new system; rules first, learn later.
- **Only LLM judgement:** not reproducible, not explainable, easy to manipulate through the document itself.
