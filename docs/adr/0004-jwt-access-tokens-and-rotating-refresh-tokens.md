# ADR-0004: JWT access tokens with rotating refresh tokens

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

The Angular UI (on Vercel) calls the API (on Render) on a different domain. The API must know who the
caller is and their role on every request, without server-side sessions (Render may restart or sleep the
container at any time).

## Decision

- **Access token:** a JWT signed with HS256, valid 15 minutes. Claims: `sub` (username), `uid`, `role`,
  `name`, `iss`, `iat`, `exp`. Sent as `Authorization: Bearer`. Verified by Spring Security's OAuth2
  resource server (signature, expiry with 60 s skew, issuer). The `role` claim becomes `ROLE_<role>`.
- **Refresh token:** 256 random bits, valid 7 days, **single-use**. Only its SHA-256 hash is stored.
  Each refresh revokes the presented token and issues a new pair (rotation).
- **Reuse detection:** presenting an already-revoked refresh token means it was copied, so all active
  sessions of that user are revoked. The refresh method uses `noRollbackFor` so the revocation commits
  even though the request is rejected.
- **HS256, not RS256:** the API is both issuer and verifier, so there's no second party that needs a
  public key. The secret comes from `JWT_SECRET` (at least 32 characters) and has no default in `prod`.
- **Login hardening:** the same error for an unknown user and a wrong password, and BCrypt runs in both
  cases, so neither the message nor the timing reveals which usernames exist. Brute-force rate limiting
  follows in a later phase.

## Consequences

- ✅ Stateless requests; any instance can serve any user.
- ✅ A stolen access token is useful for at most 15 minutes; a stolen refresh token is detected on its
  first conflicting use.
- ⚠️ An access token can't be revoked before it expires (acceptable at 15 minutes).
- ⚠️ The refresh token is returned in the JSON body, so the UI must keep it out of reach of scripts as far
  as possible (memory, not localStorage). An HttpOnly cookie would be safer against XSS but needs
  `SameSite=None; Secure` across the Vercel and Render domains, plus CSRF protection; revisit in phase 8.

## Alternatives considered

- **Server sessions:** don't survive restarts without a shared session store.
- **Supabase Auth / Keycloak:** realistic, but hides the security design this project is meant to show.
- **Long-lived access tokens only:** no way to end a stolen session.
