# Phase 2: Claim core

**Status:** ✅ Complete

## Goal

A claim can be reported, checked against its policy, triaged, assigned and handled through its lifecycle,
by the right people only, with every change audited.

## Delivered

| Item | Location |
|---|---|
| Policy port + stub adapter (plain SQL, read-only), demo policies (active, lapsed, cancelled, partial cover) | `policy/`, `V2__policy.sql`, `db/demo/V2_1` |
| Claim aggregate with the 6-state lifecycle as data (`ClaimStatus`), no setters, domain guards | `claim/domain/Claim`, `ClaimStatus` |
| Policy check: in force on the loss date, coverage for the loss type, holder match; flags, never denial | `claim/domain/PolicyCheck` |
| Triage rules (FAST_TRACK / STANDARD / COMPLEX, SIU referral), thresholds in config | `claim/domain/TriageRules`, `app.triage.*` |
| Least-loaded assignment | `claim/app/AssignmentService` |
| Idempotent FNOL (per-user key, request hash, race-safe) | `claim/app/FnolService`, `platform/idempotency/` |
| Claimant portal API and staff API with separate response types | `claim/api/PortalClaimController`, `StaffClaimController` |
| Commands: request-info, respond, withdraw, close, reopen, reassign, notes | `claim/app/ClaimCommandService` |
| Visibility (404), permission (403), status (409); `allowedActions` in every response | `ClaimAccess`, `ClaimAction` |
| ETag / If-Match (428, 412) on top of `@Version` (409) | `common/web/ETags` |
| Append-only audit trail (DB trigger, `@Immutable`, `MANDATORY` transaction) and claim timeline | `audit/`, `V3__claim_core.sql` |
| Human-readable claim numbers from a sequence: `CLM-2026-000042` | `ClaimNumberGenerator` |
| ArchUnit: no module may use another module's `infra`; common/platform depend on no business module | `ArchitectureTest` |
| ADRs 0011–0015; contract 1.1.0 (additions only) | `docs/adr/`, `docs/openapi/api.v1.json` |

## Endpoints added

| Method | Path | Role | Notes |
|---|---|---|---|
| POST | `/api/v1/portal/claims` | CLAIMANT | FNOL; `Idempotency-Key` required |
| GET | `/api/v1/portal/claims` | CLAIMANT | own claims, newest first |
| GET | `/api/v1/portal/claims/{id}` | CLAIMANT | claimant view + ETag |
| POST | `/api/v1/portal/claims/{id}/respond` | CLAIMANT | AWAITING_INFO → OPEN |
| POST | `/api/v1/portal/claims/{id}/withdraw` | CLAIMANT | → CLOSED (WITHDRAWN) |
| POST | `/api/v1/claims` | ADJUSTER | phone FNOL; `contactName` required |
| GET | `/api/v1/claims` | ADJUSTER, SUPERVISOR | queue; filters `status`, `segment`, `assigneeId` (supervisor) |
| GET | `/api/v1/claims/{id}` | staff | staff view + ETag |
| GET | `/api/v1/claims/{id}/timeline` | staff | audit events + notes |
| POST | `/api/v1/claims/{id}/request-info` | assigned ADJUSTER | OPEN → AWAITING_INFO |
| POST | `/api/v1/claims/{id}/close` | assigned ADJUSTER | OPEN → CLOSED (NO_PAYMENT until phase 6) |
| POST | `/api/v1/claims/{id}/reopen` | SUPERVISOR | CLOSED → OPEN |
| POST | `/api/v1/claims/{id}/reassign` | SUPERVISOR | target must be an active adjuster |
| POST | `/api/v1/claims/{id}/notes` | staff | internal only |

**Deviation from the design document §10:** claimant endpoints moved to `/portal/claims` and staff
endpoints to `/claims`, instead of one endpoint returning a role-dependent body (ADR-0012).

## Demo policies

| Policy | Holder | Status | Covers | Shows |
|---|---|---|---|---|
| POL-AUTO-1001 | claimant1 | ACTIVE 2026 | collision, comprehensive, glass | the happy path |
| POL-HOME-2001 | claimant1 | ACTIVE | dwelling, contents | home claims |
| POL-AUTO-1002 | claimant2 | ACTIVE | collision only | theft → NOT_COVERED |
| POL-AUTO-1003 | claimant2 | LAPSED (2025) | collision | POLICY_NOT_IN_FORCE → COMPLEX |
| POL-HOME-2002 | claimant2 | CANCELLED | dwelling | POLICY_NOT_IN_FORCE |

## Verification

| Check | Result |
|---|---|
| Unit tests | ✅ 106 (transition table: all 36 pairs; claim guards; policy check; triage boundaries; actions; ETags; assignment) |
| Integration tests (Testcontainers PostgreSQL) | ✅ 42 (FNOL 13, lifecycle 8, visibility 7, audit 1, auth 9, platform 4) |
| Coverage (merged) | 96.1% lines, 88.1% branches |
| 8 concurrent FNOLs with one key | ✅ one claim |
| 2 concurrent commands with one ETag | ✅ one 200, the other 409, no 500 (after BUG-003) |
| Audit table rejects UPDATE and DELETE from raw SQL | ✅ |
| Portal JSON contains no internal field names | ✅ |
| Existing Phase 1 database upgraded in place (V2, V2.1, V3) | ✅ |
| Contract: no Phase 1 path or schema changed; version 1.0.0 → 1.1.0 | ✅ |

## Issues found and fixed

1. **Concurrent commands returned 500 (BUG-003).** Two `request-info` calls with the same ETag: the new
   `info_request` row (IDENTITY id, inserted immediately on `save`) hit the one-open-request unique index
   before Hibernate ran the claim's version-checked UPDATE at flush. The loser got a 500 instead of a 409.
   Fixed by flushing the claim first; the loser now waits on the row lock and fails the version check.
   Found by a concurrency test written before the code was trusted.

## Deferred

- Intake as background jobs (phase 3, ADR-0014). Clean-up of old idempotency records (phase 3).
- "First contact" activity and SLA timers (phase 7). SIU visibility and cases (phase 7).
- Close guards for exposures and payments; PAID outcome (phase 6).
