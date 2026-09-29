# ADR-0020: Every API operation has an explicit, stable operationId

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 4

## Context

The Angular client will be generated from the OpenAPI contract (phase 8), and a generator names each
client method after the operation's `operationId`. springdoc derives operationIds from Java method names
and, on a collision, appends `_1`, `_2`, ... in scan order. Adding a third `list()` method in phase 4
renamed two existing operations (`list` → `list_2`, `list_1` → `list_3`): a generated client would have
silently changed method names, or even swapped two endpoints (BUG-005).

## Decision

- Every endpoint declares `@Operation(operationId = "...")`: a verb and a noun, unique, camelCase,
  e.g. `listMyClaims`, `reportLossByPhone`, `completeDocumentUpload`.
- `PlatformApiIT.everyOperationHasAnExplicitUniqueOperationId` fails the build if an operationId is
  missing, duplicated or ends in `_<n>`.
- Renaming an operationId is a breaking change for generated clients: it shows up in the contract diff
  and is treated like a renamed endpoint (ADR-0008).

## Consequences

- ✅ Client method names don't depend on method names in unrelated controllers or on class-scan order.
- ✅ Renaming a Java method no longer changes the contract.
- ⚠️ One more attribute per endpoint.

## Alternatives considered

- **A global operationId customizer (controller + method name):** no annotations, but still tied to Java
  names and unreadable ids like `staffClaimControllerQueue`.
- **Unique Java method names everywhere:** fragile; the collision rule is global across controllers.
