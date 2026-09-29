# ADR-0017: Transactional outbox with an in-process relay

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 3

## Context

Other parts of the system react to claim changes: the claimant is notified, and later SLA timers and
reports follow. Calling them directly from the claim service couples the modules, and publishing after
commit (`@TransactionalEventListener(AFTER_COMMIT)`) loses the event if the process dies right after
commit. Publishing before commit announces changes that might roll back.

## Decision

- **Write:** `OutboxService.append` (MANDATORY) inserts an `outbox_event` in the business transaction:
  the change and its event commit or roll back together (tested).
- **Payloads are self-contained:** e.g. `CLAIM_STATUS_CHANGED {claimId, claimNumber, claimantUserId,
  from, to, outcome}`, so listeners never call back into the claim module (enforced by ArchUnit for
  the notification module).
- **Relay:** `OutboxRelay` polls. Per event, an outer transaction locks the row
  (`FOR NO KEY UPDATE SKIP LOCKED`). Each interested listener runs in its own transaction
  (REQUIRES_NEW) together with a `processed_event (event_id, listener)` row, and is skipped if that row
  exists. All succeeded → `published_at`; any failed → retry after 1 min; after 10 attempts the event is
  parked (`failed_at`).
- **Lock mode:** `FOR NO KEY UPDATE`, not `FOR UPDATE`. The listener's `processed_event` insert checks its
  foreign key with a KEY SHARE lock on the event row, which `FOR UPDATE` blocks. The relay would wait for
  its own listener forever; proven by a test that hangs with `FOR UPDATE` (BUG-004).
- **Delivery guarantee:** at-least-once to each listener; exactly-once *effect* for listeners whose work
  is in our database (same transaction as the processed marker, plus a unique constraint on
  notifications per event and recipient).
- **Order:** oldest first, but a retried event can be overtaken; listeners must not depend on order.
- **No broker in v1.** The same table can feed Kafka later (a relay listener that produces to a topic)
  without changing the claim module.

## Consequences

- ✅ No lost and no phantom events; modules are decoupled by the event contract.
- ✅ A failing listener doesn't block others or repeat those that succeeded.
- ⚠️ Up to 2 s delay between the change and the reaction.
- ⚠️ The relay needs two connections while delivering (outer + listener); the pool (5) covers it.

## Alternatives considered

- **Spring application events after commit:** lost on crash; no retry.
- **Debezium CDC on the outbox table:** the standard at scale; needs Kafka Connect, not available free.
- **Direct calls from the claim service:** coupling, and a notification failure would fail the claim command.
