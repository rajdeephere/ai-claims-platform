# Phase 8: Angular UI and deployment

**Status:** ✅ UI complete and verified locally in a browser · deployment files ready (the first deploy
is run by the developer with their own accounts: [deployment.md](../deployment.md))

## Goal

One web app for the four roles, in the team's design theme. It shows the system as it is:
- actions the server allows
- amounts that need someone else's approval
- tasks that are late
- documents that go straight to storage

After that, a deployment that anyone can repeat on free tiers.

## Delivered

| Item | Location |
|---|---|
| Angular 21 app: standalone components, signals, zoneless, lazy routes, role guards | `frontend/src/app` |
| Theme: palette, Inter, Material Icons, sidebar + topbar layout, cards, tables, badges, stat cards (from the team's design theme) | `tailwind.config.js`, `styles.css`, `layout/`, `shared/` |
| Types generated from the committed OpenAPI contract; CI fails if stale | `core/api/api.schema.d.ts`, `npm run api:types` |
| Auth: access token in memory, refresh token in sessionStorage, single-flight refresh, session resume on reload | `core/auth/` |
| Interceptor: token and correlation ID for API calls only (never to storage URLs), 401 → refresh → retry once | `core/auth/auth.interceptor.ts` |
| Claimant portal: my claims, report a loss (idempotent) + documents, claim page (answer, upload, withdraw), messages | `features/portal/` |
| Direct-to-storage uploader (presigned PUT, then complete) | `shared/document-uploader.component.ts` |
| Staff: dashboard (team view for supervisors, own view for adjusters), work queue with filters | `features/staff/` |
| Claim workspace: actions from `allowedActions` with If-Match; tabs for overview, documents and AI review (accept or correct with a reason), financials (exposures, reserves, payments, recoveries), activities, timeline and notes | `features/staff/claim/` |
| Approvals (maker-checker hints), activities (open, overdue, SLA breaches), failed jobs with retry | `features/staff/` |
| SIU queue and case file with outcome | `features/siu/` |
| Backend: `GET /api/v1/users?role=` (supervisor, for reassignment); `WITHDRAW` hidden once money has gone out (BUG-014); contract 1.7.0 | `identity/api/UserDirectoryController`, `ClaimQueryService` |
| Public landing page: pitch, what it demonstrates, one-click tours by role, five-minute tour, architecture, links; live demo-server status that also wakes the API; link-preview (Open Graph) tags and image | `features/landing/`, `core/auth/demo-users.ts`, `src/index.html`, `public/og-image.png` |
| Public insurance glossary: 47 terms in 7 groups (policy, claim, roles, money, fraud, work, AI) with meaning, how the app applies it, and where to see it; search; linked from the landing page and the sidebar | `features/glossary/` |
| Unit tests (Vitest): token only to the API, single-flight refresh, session end, no refresh on login failure | `core/auth/auth.spec.ts` |
| Deployment: Render Blueprint, Vercel config, build-time API URL, keep-alive workflow, CI frontend job | `render.yaml`, `frontend/vercel.json`, `frontend/scripts/write-env.mjs`, `.github/workflows/` |
| ADRs 0029–0030; step-by-step deployment guide | `docs/adr/`, `docs/deployment.md` |

## Verification

| Check | Result |
|---|---|
| Backend unit + integration tests | ✅ 188 + 99 (+ user directory; withdraw not offered after payment) |
| Frontend unit tests | ✅ 5 |
| Production build | ✅ 355 kB initial (98 kB transferred); screens load lazily |
| Contract diff | only `/api/v1/users` added; types regenerated |
| **Browser, local stack (API 8081, SeaweedFS, Postgres, `ng serve`):** | |
| Claimant demo login → My claims | ✅ |
| Report a loss → claim number shown → PDF uploaded straight to storage → **Processed** by the AI in seconds | ✅ |
| Supervisor dashboard: claims by status, approvals, SIU, SLA breaches | ✅ |
| Claim workspace: supervisor sees only Refer to SIU and Reassign; the assigned adjuster sees Ask / Refer / Propose denial / Close | ✅ |
| Documents & AI: extraction pending review with Correct / Accept; fraud score with its reason | ✅ |
| Adjuster reserve ₹15,000 → "waits for a supervisor" → supervisor approves in Approvals | ✅ |
| Adjuster payment ₹12,000 → PENDING_APPROVAL, committed ₹12,000, available ₹3,000 → supervisor approves → **Issued** with the rail's reference | ✅ |
| Refer to SIU → siu1 sees the case → records CLEARED with findings → claim OPEN | ✅ |
| Claimant messages: "A payment of Rs 12000.00 ..." (BUG-012 fix, live); nothing mentions SIU | ✅ |
| Reload keeps the session (refresh token in sessionStorage) | ✅ |
| SeaweedFS answers the browser's CORS preflight for the presigned PUT | ✅ |

## Issues found and fixed

1. **Withdraw was offered after a payment (BUG-014).** Found in the browser: the claimant's page showed
   "Withdraw claim" on a claim with ₹12,000 paid. The server would refuse it (`PAYMENT_ALREADY_ISSUED`),
   but `allowedActions` only looked at the status. `ClaimQueryService` now removes `WITHDRAW` while money
   has gone out or is in progress. Covered by an integration test.
2. **Generated types say every field is optional.** springdoc marks no response field as required.
   Responses are used as `Required<...>`, fields documented as nullable are guarded, and two template
   diagnostics are switched off (ADR-0029).
3. **A refresh race by design.** A rotating one-time refresh token plus parallel 401s would revoke the
   session. The refresh is single-flight, and that is unit-tested.

4. **Flyway refused the Supabase database (BUG-015).** Found in the first run against the real project:
   "non-empty schema public but no schema history table". Supabase's "automatic RLS" option had created
   `public.rls_auto_enable()`. The prod profile now baselines at version **0** (the default baseline, 1,
   would have skipped V1). Verified: 10 migrations applied (15 s from Tokyo); FNOL, a presigned PUT to
   Supabase Storage (200), completion, and the background AI (PROCESSED) all work; Supabase answers the
   browser's CORS preflight with `*`.

## Not done here

- **The actual first deployment:** it needs the developer's Supabase, Render and Vercel accounts;
  `docs/deployment.md` walks through it and ends with a checklist.
- Component tests beyond auth; end-to-end browser tests in CI (Playwright).
- Accessibility pass (keyboard focus in dialogs, contrast of pastel badges), mobile layout for staff.
