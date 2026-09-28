# ADR-0009: One error model with stable error codes

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Errors come from three places: controllers and services, Spring MVC itself (unknown route, bad JSON),
and the security filters (401/403, which run before any controller). The UI must react to specific
failures (over authority limit, SIU hold, concurrent change) without parsing messages.

## Decision

Every error, from every source, has one body:

```json
{ "timestamp": "...", "status": 422, "error": "Unprocessable Entity", "code": "AUTHORITY_EXCEEDED",
  "message": "Amount exceeds your authority limit", "path": "/api/v1/...", "correlationId": "...",
  "violations": [] }
```

- `code` is stable and machine-readable; `message` is for people and may change.
- Business exceptions extend `ApiException(status, code, message)`, so a new exception needs no new
  handler.
- `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` (Spring keeps choosing the right
  status for its own exceptions) and handles `AccessDeniedException` from method security.
- `SecurityErrorHandlers` writes the same body for 401/403 raised in the filter chain, plus
  `WWW-Authenticate: Bearer`.
- Unexpected exceptions return 500 with no details; the cause is logged with the correlation ID.
- A resource the caller may not see returns 404, not 403, so its existence isn't revealed.

## Consequences

- ✅ The UI switches on `code`; support finds the log lines through `correlationId`.
- ⚠️ Codes are part of the API contract and must not be renamed casually.

## Alternatives considered

- **RFC 7807 `ProblemDetail` as-is:** standard, but it has no stable code field unless extended; our
  body carries the same information in the shape already used by ClaimFlow.
