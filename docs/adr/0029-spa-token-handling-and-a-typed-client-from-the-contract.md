# ADR-0029: The Angular client: token handling, single-flight refresh, and types from the contract

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 8

## Context

The API issues a 15-minute access token and a rotating, one-time refresh token with reuse detection
(ADR-0004). If a refresh token is presented twice, the whole session is revoked. A single-page app
therefore has two problems:

- **Where to keep the tokens.** Storage the browser can read is exposed to XSS. Cookies need CSRF
  protection and a same-site API, which Vercel + Render (two domains) doesn't give.
- **Parallel calls.** When the access token expires, several calls get 401 at the same moment. A naive
  interceptor would refresh once per call, and the second refresh would look like theft and log the
  user out.

The UI also needs the API's shapes (about 60 DTOs), and they must not drift from the server.

## Decision

- **Token storage.**
  - The access token lives only in memory (a signal). A reload loses it, so it's refreshed at start-up
    in an app initializer.
  - The refresh token is kept in `sessionStorage`. It survives a reload but not closing the tab, and it
    isn't shared between tabs. Logout revokes it on the server.
- **Single-flight refresh.** `AuthService.refresh()` returns one shared observable (`shareReplay`)
  while a refresh is running. All 401s wait for the same call, then retry once with the new token. If
  the refresh fails, the session is over: storage is cleared and the user goes to login. A unit test
  proves it: two parallel 401s produce exactly one refresh request and two retries.
- **The interceptor adds the token only for our API's base URL.** Presigned storage URLs, which are
  third-party hosts, never see it (unit-tested). Every API call gets an `X-Correlation-Id`, so a
  request can be found in the server logs.
- **Server-driven UI.**
  - Buttons come from the claim's `allowedActions`; the client never re-implements the rules.
  - Commands send the ETag they read as `If-Match`. On 412 the screen reloads and says why.
  - FNOL and payment forms make one `Idempotency-Key` per intended submission, so a retry after a
    timeout can't create a second claim or payment.
- **Types generated from the committed contract.** `npm run api:types` (openapi-typescript) turns
  `docs/openapi/api.v1.json` into `api.schema.d.ts`. CI fails if the file is stale, so a breaking API
  change breaks the frontend build, not production. springdoc marks every response field optional, so
  responses are used as `Required<...>`. Fields the API documents as nullable are still guarded in
  templates, and the two "unnecessary `??` / `?.`" diagnostics are switched off for that reason.
- **Theme.** Angular 21 standalone components, signals, zoneless, Tailwind 3 and Angular Material, all
  following the team's design theme (palette, type, layout). Money is shown as INR with two decimals.

## Consequences

- ✅ No token in persistent storage. Closing the tab ends the session. The concurrent-401 case is
  handled and tested.
- ✅ Contract drift is caught at build time. The UI shows exactly the actions the server will accept.
- ⚠️ `sessionStorage` is still readable by XSS in the tab's lifetime. The mitigation is Angular's
  template escaping, no `innerHTML`, and strict security headers on Vercel. A backend-for-frontend with
  httpOnly cookies is the stronger design if the UI and API ever share a domain.
- ⚠️ Nullability isn't in the generated types; adding `requiredMode` to the DTOs' schemas would fix it
  at the source.

## Alternatives considered

- **localStorage for both tokens:** survives browser restarts, which also lengthens the window for a
  stolen token.
- **httpOnly refresh cookie:** the best option on one domain. Across `vercel.app` and `onrender.com` it
  needs `SameSite=None` and CSRF tokens.
- **Hand-written TypeScript interfaces:** they drift silently.
