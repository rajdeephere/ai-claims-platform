# ADR-0023: Rate limits: failed logins and outbound LLM calls

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 5

## Context

Login is open to the internet, so passwords can be guessed. The LLM provider's free tier allows about 30
requests per minute; exceeding it returns 429s and may get the key throttled.

## Decision

- **Login:** only **failed** attempts count: 5 per minute per username (case-insensitive) and 30 per
  minute per client IP. Over either limit, even the correct password gets 429 `TOO_MANY_LOGIN_ATTEMPTS`
  with `Retry-After` until the bucket refills. Successful logins are never slowed down. Buckets live in a
  Caffeine cache (idle entries expire after 15 min, bounded size). Behind Render's proxy the client IP comes
  from `X-Forwarded-For` (`server.forward-headers-strategy: framework`).
- **LLM:** a token bucket of 25 calls per minute in front of every model call. When it's empty, the job
  gets a retryable "rate limited" error and the queue retries with backoff; a provider 429 is handled the
  same way.
- Both use Bucket4j (token buckets with greedy refill).

## Consequences

- ✅ Password guessing is limited to 5 per minute per account without locking out genuine users for long.
- ✅ The provider's limit is respected by design, not discovered through errors.
- ⚠️ In-memory buckets are per instance; with several instances, move them to a shared store (Redis or
  Bucket4j's PostgreSQL backend).
- ⚠️ An attacker can deliberately block a known username for a minute at a time; acceptable at this scale.

## Alternatives considered

- **Account lockout after N failures:** a denial-of-service lever on any known username.
- **CAPTCHA:** needs a third-party service and UI work; revisit with the Angular login.
