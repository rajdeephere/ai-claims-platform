# ADR-0030: Deployment topology: static UI calls the API directly; API URL fixed at build time

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 8

## Context

The UI is static files on Vercel, and the API is a Docker service on Render that sleeps when idle
(ADR-0007). The UI needs to know the API's URL. There are two ways for the browser to reach the API:

- directly, across origins (CORS)
- through a Vercel rewrite, on the same origin

Documents go from the browser straight to Supabase Storage via presigned URLs, which is cross-origin
either way.

## Decision

- **Direct calls with CORS.** The API allows exactly the origins in `CORS_ALLOWED_ORIGINS`.
  - It exposes `ETag`, `Location`, `X-Correlation-Id` and `Idempotent-Replayed`.
  - It allows `If-Match`, `Idempotency-Key` and `X-Correlation-Id`.
- **The API URL is baked in at build time.** `scripts/write-env.mjs` writes
  `environment.production.ts` from Vercel's `API_URL` variable and fails the build without it. There is
  no runtime config fetch before bootstrap.
- **Infrastructure as files.**
  - `render.yaml` (Blueprint): Docker, free plan, health check. Secrets are declared with
    `sync: false`, and `JWT_SECRET` is generated. `autoDeployTrigger: checksPass` means a commit is
    deployed only after its GitHub CI checks pass.
  - `frontend/vercel.json`: build, output, SPA rewrite, security headers.
  - `.github/workflows/keep-alive.yml`: pings the health check every 10 minutes (Render sleep, Supabase
    pause).
- **CI builds the frontend too:** it regenerates the types (and must find no diff), runs the unit
  tests, and does a production build.

## Consequences

- ✅ One hop: the browser talks to Render directly. A cold start is visible as such ("the server may be
  waking up"), not as a proxy timeout.
- ✅ A new environment is a new build with a different `API_URL`; the same image and bundle shape
  everywhere else.
- ⚠️ Changing the API's URL needs a frontend rebuild (Vercel: redeploy).
- ⚠️ Preview deployments on other Vercel URLs are rejected by CORS unless added to
  `CORS_ALLOWED_ORIGINS`.

## Alternatives considered

- **A Vercel rewrite `/api/*` → Render (same origin, no CORS):** it's attractive, but the proxy's
  timeout is shorter than Render's cold start. It also puts a second network hop and another
  vendor's limits on every call.
- **Runtime `config.json` loaded before bootstrap:** one bundle for all environments, but one more
  request and one more failure mode at start-up. Not worth it for one environment.
