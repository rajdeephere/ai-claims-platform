# ADR-0011: Idempotent FNOL with per-user Idempotency-Key

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 2

## Context

A claimant on a mobile connection presses "Submit" and the response is lost. The app retries, or the
claimant presses again. Without protection, one loss becomes two claims: two adjusters, possibly two
payments later.

## Decision

- `POST /portal/claims` and `POST /claims` require an `Idempotency-Key` header (8–100 characters, e.g.
  a UUID generated once per claim form). A missing key is 400 `IDEMPOTENCY_KEY_REQUIRED`.
- Table `idempotency_record (user_id, idempotency_key) → operation, request_hash, resource_id`. Keys are
  scoped per user, so two users can't collide and nobody can replay someone else's request.
- The request is normalised (trimmed, upper-cased policy number, amounts at scale 2, defaults applied) and
  hashed with SHA-256, so `1500` and `1500.00` are the same request.
- Same key and same hash: the original claim is returned, 201 with `Idempotent-Replayed: true`. Same key
  and a different hash: 422 `IDEMPOTENCY_KEY_REUSED`.
- The record is inserted in the **same transaction** as the claim.
- **Race:** two requests with one key can both find no record. The record entity implements
  `Persistable.isNew() = true`, so the insert is a plain INSERT (not merge's SELECT then INSERT), and the
  loser fails on the primary key at commit. `FnolService` catches that after its transaction has rolled
  back and, in a new transaction, returns the winner's claim. Verified with 8 concurrent requests.

## Consequences

- ✅ A retried FNOL never creates a second claim, including under concurrency.
- ✅ No distributed lock, no cache: the database's primary key is the arbiter.
- ⚠️ Records accumulate; a clean-up job (phase 3) will delete those older than 24 hours.
- ⚠️ The replay returns the claim's *current* state, not a byte-for-byte copy of the first response.

## Alternatives considered

- **Natural de-duplication (same policy, date, description):** two genuine losses can look alike.
- **Store the whole first response:** exact replay, but a copy of claim data that goes stale.
- **Client-side button disabling only:** doesn't survive a lost response or a retry from the network layer.
