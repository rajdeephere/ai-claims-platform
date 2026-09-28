# ADR-0012: Separate claimant and staff APIs; ETag / If-Match on every claim command

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 2

## Context

Claimants and staff look at the same claim, but a claimant must never see the fraud score, the segment,
the flags, the assigned adjuster or internal notes. The design document proposed one `GET /claims/{id}`
that returns a different body per role. That makes the OpenAPI schema a union, the generated client
harder to use, and a leak only one forgotten `if` away.

Claims are also edited by several people: an adjuster, a supervisor, the claimant. A command based on a
page loaded minutes ago must not overwrite a change made since.

## Decision

**Two APIs:**

| Path | Roles | Response types |
|---|---|---|
| `/api/v1/portal/claims/**` | CLAIMANT | `PortalClaimResponse`, `PortalClaimSummary` |
| `/api/v1/claims/**` | ADJUSTER, SUPERVISOR, SIU | `StaffClaimResponse`, `StaffClaimSummary` |

The portal types simply have no internal fields, so there's nothing to forget to hide. SIU_REVIEW shows
as IN_REVIEW to the claimant. An integration test checks that the raw portal JSON doesn't contain the
internal field names.

**Commands, not field updates:** `request-info`, `respond`, `withdraw`, `close`, `reopen`, `reassign`,
each a `POST` that runs one domain method. There is no `PATCH status`.

**Optimistic concurrency, two layers:**

1. Every claim response has `ETag: "<version>"`. Every command requires `If-Match`:
   missing is 428 `IF_MATCH_REQUIRED`, different is 412 `VERSION_MISMATCH`.
2. JPA `@Version` catches two requests that both passed step 1 at the same moment: 409
   `CONCURRENT_UPDATE`.

Notes don't change the claim and need no `If-Match`.

## Consequences

- ✅ A claimant response can't leak internal data by construction; each API has a precise schema.
- ✅ A stale screen can't overwrite a newer change, and "forgot the header" is an error, not a silent
  overwrite.
- ⚠️ Two controllers share most of the service layer; that's intended.
- ⚠️ Parent-first flushing is needed when a command also inserts child rows (BUG-003).

## Alternatives considered

- **One endpoint, role-based DTO or `@JsonView`:** one mistake exposes internal data; the schema is vague.
- **Last write wins:** unacceptable for money and status.
- **Pessimistic locks held across the user's think time:** impossible over stateless HTTP.
