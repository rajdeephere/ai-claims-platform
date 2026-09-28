# ADR-0015: Claim access checked in three steps: visibility, permission, status

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 2

## Context

Role annotations alone can't decide "may this adjuster see this claim": that depends on data (who is
assigned, who filed it). And "you may never do this" and "you can't do this right now" are different
answers the UI needs to tell apart.

## Decision

Every claim command goes through the same steps, in this order:

| Step | Question | Failure |
|---|---|---|
| 1. Role on the endpoint | Portal is CLAIMANT only; staff API is ADJUSTER/SUPERVISOR/SIU | 403 |
| 2. Visibility (`ClaimAccess`) | CLAIMANT: filed it · ADJUSTER: assigned, or took the FNOL · SUPERVISOR: all · SIU: none until phase 7 | **404**, identical to a claim that doesn't exist |
| 3. Permission (`ClaimAction.permits`) | e.g. only the assigned adjuster closes; only a supervisor reopens or reassigns | 403 |
| 4. If-Match | the caller saw the current version | 428 / 412 |
| 5. Status (domain method) | the lifecycle allows the move now | 409 `INVALID_TRANSITION` |

`allowedActions` in every response is computed from steps 3 and 5 (`ClaimAction.allowed`), so the UI
shows exactly the buttons the server would accept and never re-implements the rules.

Queues follow the same visibility: an adjuster's `GET /claims` is always their own, whatever filter they
send.

## Consequences

- ✅ Claim ids can't be probed: someone else's claim and a missing one look the same.
- ✅ Clear error meaning for the UI: 404 hide, 403 never, 409 not now.
- ⚠️ The visibility rule is in code, not SQL row-level security; every new query must apply it (queues
  do, through the adjuster filter).

## Alternatives considered

- **403 for someone else's claim:** confirms the claim exists.
- **PostgreSQL row-level security:** strong, but needs the user identity in the database session, which
  the pooled connections don't carry.
