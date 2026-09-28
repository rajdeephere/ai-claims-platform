# ADR-0008: URI versioning and a committed, build-checked OpenAPI contract

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

The Angular client will be generated from the OpenAPI definition (phase 8). An accidental breaking
change (a renamed field, a changed status code) would break the UI with no warning.

## Decision

- All endpoints live under `/api/v1`. A breaking change gets `/api/v2` next to v1; additions bump the
  contract's minor version (`info.version`).
- The OpenAPI group `v1` is published at `/v3/api-docs/v1` and committed as `docs/openapi/api.v1.json`.
- `PlatformApiIT` compares the live definition with the committed file (canonical JSON, sorted keys, LF),
  so any API change fails the build until the file is regenerated with
  `mvn verify -Dopenapi.update=true` and the diff is reviewed.
- Every operation documents the shared `ApiError` schema, the correlation header, 400 and 500; secured
  operations also 401 and 403; `@DocumentedErrors` adds endpoint-specific statuses.
- Statuses springdoc can't infer from `ResponseEntity` (201, 204) are declared with `@ApiResponse`.

## Consequences

- ✅ API changes show up as a reviewed diff in the pull request.
- ✅ The contract that generates the client is the one that was tested.
- ⚠️ Every API change needs a regeneration step.

## Alternatives considered

- **Contract-first (write YAML, generate server stubs):** strongest guarantee, slower to iterate alone.
- **Consumer-driven contracts (Pact):** useful with several consuming teams; there is one client.
