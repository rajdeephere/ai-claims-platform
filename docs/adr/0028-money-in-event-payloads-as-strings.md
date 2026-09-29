# ADR-0028: Money in event payloads is a string

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 7

## Context

Outbox payloads are `Map<String, Object>` stored as jsonb. A `BigDecimal` of 3800.00 is written as the JSON
number `3800.00`. When it's read back into a Map, Jackson makes it a `Double`, and `toString()` gives
`3800.0`. Since phase 6 the claimant's notification read "A payment of Rs 3800.0 ... has been issued"
(BUG-012). The integration test checked only the subject line, so nobody noticed.

## Decision

Amounts in event payloads are strings with exactly two decimals (`BigDecimal.toPlainString()` at scale 2),
for example `"3800.00"`. `FinancialsEvents.AMOUNT` documents this, and listeners use the text as is.
The integration test now checks the full notification body.

## Consequences

- ✅ What a listener shows is exactly what was paid. No rounding and no float artefacts, whatever library
  reads the payload.
- ⚠️ Audit entries (`before` / `after`) still store amounts as JSON numbers. They are data for people and
  the timeline, not text to display. A UI must format them (phase 8).

## Alternatives considered

- **Jackson `USE_BIG_DECIMAL_FOR_FLOATS` for the payload mapper:** it fixes this reader, but every
  consumer would have to be configured the same way. The next one (a report, another service) gets
  doubles again. A string is correct whoever reads it.
- **Typed event classes instead of maps:** the better long-term contract, but a larger change than the
  bug needs.
