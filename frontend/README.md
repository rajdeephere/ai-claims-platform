# AI Claims: web app

Angular 21 (standalone components, signals, zoneless), Tailwind 3 and Angular Material. The look
follows the team's design theme. One app serves all four roles; each role sees its own navigation.

| Role | Screens |
|---|---|
| Claimant | my claims, report a loss (+ documents), claim status, answer questions, withdraw, messages |
| Adjuster | dashboard, work queue, claim workspace (overview, documents and AI review, financials, activities, timeline and notes), activities |
| Supervisor | the above for all claims, plus approvals, SIU cases, SLA breaches, failed jobs |
| SIU | case queue, case file, outcome |

## Run locally

The API must be running (see the main README: `docker compose up -d` and `mvn spring-boot:run` in
`backend/`).

```bash
npm ci
npm start            # http://localhost:4200, calls the API on http://localhost:8081
npm test             # unit tests (Vitest)
```

Sign in with a demo account (the buttons on the login page), password `Password1!`.

## How it talks to the API

- **Types:** `src/app/core/api/api.schema.d.ts` is generated from the backend's committed contract.
  After an API change, run `npm run api:types`; CI fails if it's stale.
- **Auth:** the access token is held in memory, the refresh token in sessionStorage, and refresh is
  single-flight (ADR-0029).
- **Commands:** they send the ETag they read as `If-Match`. Buttons come from `allowedActions`. FNOL and
  payment requests carry an `Idempotency-Key`.
- **Documents:** they go from the browser straight to storage via presigned URLs. The interceptor never
  sends the token there.

## Build for production

`npm run build:prod` with `API_URL` set (Vercel does this; see `docs/deployment.md`).
