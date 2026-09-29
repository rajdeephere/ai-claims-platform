# ADR-0025: A pessimistic row lock on the exposure for payments

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 6

## Context

Claims use optimistic locking: an ETag / If-Match header plus `@Version` (ADR-0012). The client proves it
saw the latest version, and a conflicting write gets 409. That fits commands where a person looks at the
claim and then acts.

Payments are different:

- Two payment requests on one exposure don't conflict on any field. Each one reads "available" and inserts
  a new row, so optimistic locking on the exposure row would never fire.
- Both could pass the check and together spend more than the reserve (write skew).
- Payment requests shouldn't need an If-Match header either: the request is idempotent by its own key.

## Decision

Every write to an exposure takes the lock first: payment requests, reserve changes and closes, reserve
approvals, and payment issuing. They load the exposure
with `SELECT ... FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)` on `findByIdForUpdate`). Only then do they sum
the committed payments and check "available". Concurrent requests on the same exposure queue up for the
few milliseconds of the transaction. Different exposures never wait for each other.

Reserve changes and closes by a person also need If-Match on the exposure's version, as claims do.
Those are decisions made after looking at the numbers, so a stale view must fail with 412.

## Consequences

- ✅ Tested: two parallel requests for 3,000 against a 4,000 reserve: exactly one gets 201, the other gets
  `INSUFFICIENT_RESERVE`.
- ✅ The lock is short: no network calls happen while holding it (the rail is called outside the
  transaction, ADR-0024).
- ⚠️ Lock order matters if a transaction ever locks two exposures. Today none does. If one ever must,
  lock them in id order.

## Alternatives considered

- **Optimistic version bump on the exposure for every payment:** it works, but conflicting requests fail
  instead of waiting, and the client has to retry with a new idempotency key.
- **SERIALIZABLE isolation:** correct, but it fails with serialization errors that need retry logic around
  every call, and it affects the whole transaction rather than one row.
- **A running "committed" column on the exposure:** fast to read, but it duplicates what the payment rows
  already say and can drift.
