# Phase 1: Foundation

**Status:** ✅ Complete

## Goal

A running, tested, deployable skeleton: every later phase only adds business modules.

## Delivered

| Item | Location |
|---|---|
| Maven project: Java 17 (compiled with `release 17`), Spring Boot 3.5.5 | `backend/pom.xml` |
| Local PostgreSQL 16 on port 5434 | `docker-compose.yml` |
| Flyway schema (`V1__identity`) + demo users (`db/demo/V1_1`), `ddl-auto: validate` | `backend/src/main/resources/db/` |
| Login, refresh (rotation + reuse detection), logout, `GET /me` | `identity/` |
| Stateless JWT security (HS256), role authorities, CORS, method security | `identity/config/` |
| One error model: `ApiError` with stable `code`, for MVC and security-filter errors | `common/error/`, `identity/config/SecurityErrorHandlers` |
| Correlation ID filter (safe format, MDC, echoed) | `common/correlation/` |
| OpenAPI v1 group, bearer scheme, standard error responses, committed contract | `common/openapi/`, `docs/openapi/api.v1.json` |
| Module and layer rules | `ArchitectureTest` |
| Layered Docker image sized for 512 MB | `backend/Dockerfile` |
| CI: unit, then integration + contract + coverage, then Docker image | `.github/workflows/ci.yml` |
| ADRs 0001–0010 | `docs/adr/` |

## Demo users

Password for all: `Password1!`

| Username | Role | Authority limit |
|---|---|---|
| claimant1, claimant2 | CLAIMANT | 0 |
| adjuster1, adjuster2 | ADJUSTER | 5,000.00 |
| supervisor1 | SUPERVISOR | 50,000.00 |
| siu1 | SIU | 0 |

## Endpoints

| Method | Path | Auth | Result |
|---|---|---|---|
| POST | `/api/v1/auth/login` | public | 200 tokens; 401 `INVALID_CREDENTIALS`; 400 `VALIDATION_FAILED` |
| POST | `/api/v1/auth/refresh` | public | 200 new tokens; 401 `INVALID_REFRESH_TOKEN` (reuse ends all sessions) |
| POST | `/api/v1/auth/logout` | public | 204, idempotent |
| GET | `/api/v1/me` | bearer | 200 user with current authority limit |
| GET | `/actuator/health` | public | 200 `UP` |
| GET | `/swagger-ui.html` | public | Swagger UI |

## Verification

| Check | Result |
|---|---|
| Unit tests (`mvn test`) | ✅ 21 (auth rules 8, tokens 5, correlation filter 4, architecture 4) |
| Integration tests (`mvn verify`, Testcontainers PostgreSQL 16) | ✅ 13 (auth API 9, platform 4) |
| Coverage (unit + integration merged) | 88.5% lines, 69.2% branches |
| Contract check fails when the API changes, passes after regeneration | ✅ (seen during BUG-002) |
| App against docker-compose: Flyway applies V1 and V1.1, login and `/me` work, Swagger 200 | ✅ |
| Docker image: 252 MB, starts with a 512 MB limit, 268 MiB used, health UP | ✅ |
| Container cold start with 512 MB | about 57 s, so expect about a minute on Render's free tier |

## Issues found and fixed

1. **Architecture rules never ran (BUG-001).** ArchUnit rules written as `static final ArchRule` fields
   reported "Tests run: 0" and passed. Surefire 3.5 skips tests whose source is a field. Proved it with a
   rule that must fail (it passed as a field and failed as a method), then rewrote the rules as methods.
2. **Logout documented as 200 instead of 204 (BUG-002).** springdoc can't see the status set inside
   `ResponseEntity`. Found by reading the generated contract; fixed with `@ApiResponse(responseCode = "204")`.
   The contract check then failed as intended until the file was regenerated.

## Deferred

- Login rate limiting (Bucket4j): with the AI work in phase 5, where an outbound limiter is needed too.
- Refresh token in an HttpOnly cookie: decided in phase 8 with the UI (ADR-0004).
