# ADR-0013: Append-only audit trail, written in the business transaction

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 2

## Context

Claims handling is regulated: every decision must be traceable to a person (or the system), a time and a
reason. An audit trail that can be edited, or that can miss entries when something fails halfway, is
worthless in a dispute.

## Decision

- Table `audit_event`: entity, claim, action, actor (id + name, `system` for automatic steps), old value
  and new value as JSONB, reason, correlation ID, time.
- **Same transaction:** `AuditService.record` uses `Propagation.MANDATORY`. It can only run inside the
  caller's business transaction, so a change and its audit entry commit or roll back together. Called
  outside a transaction, it fails immediately.
- **Append-only, three ways:** `AuditEvent` is `@Immutable` (Hibernate never updates it); it has no
  setters; and a PostgreSQL trigger rejects any `UPDATE` or `DELETE` on the table, even from a manual SQL
  session. An integration test proves the trigger.
- Every claim status change is recorded as `STATUS_CHANGED` with the old and new status, plus
  domain-specific events (`POLICY_CHECKED`, `CLAIM_TRIAGED`, `CLAIM_ASSIGNED`, `INFO_REQUESTED`, ...).
- The staff timeline (`GET /claims/{id}/timeline`) merges audit events and notes, oldest first.

## Consequences

- ✅ Every change has a who, when, what and why; the correlation ID links it to the request's log lines.
- ✅ History can't be rewritten silently.
- ⚠️ A genuine correction is a new, compensating event, never an edit.
- ⚠️ The table grows forever; partitioning by month is the answer at real volume.

## Alternatives considered

- **Hibernate Envers:** automatic row versioning, but it records column changes, not business intent
  ("why"), and adds a shadow table per entity.
- **Audit in logs only:** logs are rotated and not queryable per claim.
- **Writing the audit after commit (event listener):** a crash between commit and audit loses the entry.
