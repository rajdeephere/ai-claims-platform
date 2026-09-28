# ADR-0005: Authority limits are read from the database, not the token

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Each adjuster and supervisor has an authority limit: the largest reserve or payment they may approve
alone. Putting it in the JWT would save a query per decision.

## Decision

The JWT carries identity and role only. Every money decision loads the user's current limit from
`app_user.authority_limit` inside the same transaction. `GET /api/v1/me` also returns the limit from the
database. In v1 limits are set only by migration; there is no admin UI.

## Consequences

- ✅ Lowering a limit (or deactivating a user) takes effect on the next decision, not when the token
  expires.
- ✅ Nobody can change a limit through the application, which keeps segregation of duties.
- ⚠️ One extra primary-key lookup per approval: negligible.

## Alternatives considered

- **Limit in the token:** a lowered limit would stay usable for up to 15 minutes.
- **Limit per role:** real carriers set limits per person (experience, licence), not per job title.
