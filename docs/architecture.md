# Architecture

A modular monolith: one Spring Boot API owns all claim state in PostgreSQL. The Angular UI, document
storage, the LLM and the stubbed external systems connect through ports. Full design:
[design/design-document-v1.md](design/design-document-v1.md).

```mermaid
flowchart TB
  UI["Angular UI · Vercel"]
  subgraph API["Spring Boot API · Render (Docker, 512 MB)"]
    WEB["Web and security<br/>REST, JWT roles, correlation ID"]
    DOM["Claim domain<br/>state machine, authority, maker-checker"]
    JOB["Job runner<br/>DB job queue, timers, outbox"]
    PORTS["Ports: repositories, documents, LLM, policy, payment"]
  end
  PG[("PostgreSQL · Supabase")]
  ST[("Object storage · Supabase S3")]
  LLM["Groq LLM"]
  STUB["Stubbed policy admin and payment rails"]
  UI -- "REST + JWT" --> WEB --> DOM --> PORTS
  JOB --> PORTS
  PORTS --> PG & ST & LLM & STUB
  UI -. "presigned upload" .-> ST
```

## Modules

| Module | Responsibility | Since |
|---|---|---|
| `common` | error model, correlation ID, clock, OpenAPI | phase 1 |
| `identity` | users, roles, login, JWT, refresh tokens, current user | phase 1 |
| `claim` | FNOL, claim state machine, policy check, triage, assignment, notes, portal and staff APIs | phase 2 |
| `policy` | policy port and stub adapter | phase 2 |
| `audit` | append-only audit trail, claim timeline | phase 2 |
| `platform` | idempotency keys; job queue, timers, ops API; transactional outbox and relay; housekeeping | phase 2–3 |
| `notification` | in-app claimant notifications, fed only by claim events | phase 3 |
| `document` | storage port (S3 adapter), presigned upload/download, verification (type, size, hash), document lifecycle | phase 4 |
| `ai` | LLM port (Groq, stub), document preparation, extraction + validation, fraud score (implements the claim module's `RiskAssessmentPort`), human review | phase 5 |
| `financials` | exposures, reserves, payments, recoveries, authority limits, maker-checker approvals, payment rail port (implements the claim module's `ClaimFinancialsPort`) | phase 6 |
| `siu` | SIU cases: referral (rule, manual), case-based visibility, outcome (implements the claim module's `SiuReferralPort`) | phase 7 |
| `activity` | tasks created from outbox events, SLA timers and escalation, supervisor dashboard | phase 7 |

Layers inside every module: `api` → `app` → `domain`, with `infra` for adapters and `config` for wiring.
Rules enforced by `ArchitectureTest` ([ADR-0002](adr/0002-modular-monolith-with-enforced-boundaries.md)).

## Request path (phase 1)

1. `CorrelationIdFilter` (first filter) accepts or creates `X-Correlation-Id` and puts it in the MDC.
2. Spring Security validates the bearer JWT (signature, expiry, issuer) and maps `role` to `ROLE_*`.
   Failures return a 401/403 `ApiError` from `SecurityErrorHandlers`.
3. The controller calls an application service; the service opens the transaction.
4. Exceptions become an `ApiError` in `GlobalExceptionHandler`.

## A claim command (phase 2)

```mermaid
sequenceDiagram
  participant UI
  participant C as Controller
  participant S as ClaimCommandService
  participant A as ClaimAccess
  participant D as Claim (domain)
  participant DB as PostgreSQL
  UI->>C: POST /claims/{id}/request-info, If-Match "3"
  C->>S: requestInformation(id, ifMatch, message, user)
  S->>A: loadVisible (404) + requirePermission (403)
  S->>S: ETags.requireMatch (428 / 412)
  S->>D: requestInformation() (409 if not OPEN)
  S->>DB: flush claim UPDATE ... WHERE version = 3 (row lock)
  S->>DB: INSERT info_request, INSERT audit_event x2
  DB-->>S: commit (all or nothing)
  C-->>UI: 200 + ETag "4" + allowedActions
```

Every command also appends its audit entries and outbox events in that same transaction.

## Background work (phase 3)

```mermaid
flowchart LR
  FNOL["FNOL transaction<br/>claim + audit + event + first job"] --> VP
  subgraph Jobs["job table, polled every 2 s (SKIP LOCKED, leases, backoff)"]
    VP["VERIFY_POLICY<br/>policy call outside any tx"] --> CA["COMPLETE_ASSESSMENT<br/>triage, assign"]
    VP -.-> TO["ASSESSMENT_TIMEOUT<br/>timer, 10 min"]
    TO -.-> CA
  end
  subgraph Outbox["outbox_event, relayed every 2 s"]
    EV["CLAIM_SUBMITTED<br/>CLAIM_STATUS_CHANGED<br/>INFO_REQUESTED"]
  end
  FNOL --> EV
  CA --> EV
  EV --> NOTIFY["ClaimantNotifier<br/>(own tx + processed_event)"]
```

- Jobs commit with the change that needs them; handlers are idempotent; DONE only while holding the
  lease ([ADR-0016](adr/0016-database-job-queue.md)).
- Events commit with the change they describe; each listener handles each event once
  ([ADR-0017](adr/0017-transactional-outbox-with-in-process-relay.md)).
- Intake is a chain of jobs; FNOL answers immediately
  ([ADR-0018](adr/0018-claim-intake-as-jobs.md)).

## Document upload (phase 4)

```mermaid
sequenceDiagram
  participant B as Browser
  participant API
  participant S3 as Object storage (SeaweedFS / Supabase)
  B->>API: POST /claims/{id}/documents {name, type, size}
  API-->>B: 201 PENDING_UPLOAD + presigned PUT (type and exact size signed, 5 min)
  B->>S3: PUT file (never through the API)
  B->>API: POST /documents/{id}/complete
  API->>S3: HEAD + GET (outside any transaction)
  API->>API: Tika type from bytes, SHA-256, duplicate check
  API-->>B: UPLOADED / duplicate / 422 REJECTED (object deleted)
```

See [ADR-0019](adr/0019-documents-presigned-upload-and-verification.md).

## AI assessment (phase 5)

```mermaid
flowchart LR
  UP["DOCUMENT_UPLOADED<br/>(outbox)"] --> JOB["ASSESS_DOCUMENT job<br/>outside any transaction"]
  JOB --> PREP["PDF text / rendered scan /<br/>resized photo"]
  PREP --> LLM["LLM port<br/>Groq or stub, 25/min"]
  LLM --> VAL{"ExtractionValidator<br/>1 retry"}
  VAL -- valid --> SAVE["assessment + provenance<br/>(model, prompt, file hash)"]
  VAL -- invalid twice / outage --> FAIL["FAILED: manual review"]
  SAVE --> RISK["REVIEW_CLAIM_RISK job"]
  FAIL --> RISK
  RISK --> SCORE["fraud score: rules + LLM signals<br/>(RiskAssessmentPort)"]
  SCORE --> TRIAGE["triage / SIU at intake,<br/>flag afterwards"]
  ADJ["adjuster accept / override<br/>(reason required)"] --> RISK
```

The claim module defines `RiskAssessmentPort`; the ai module implements it, so modules stay acyclic
([ADR-0022](adr/0022-explainable-fraud-score-behind-a-port.md)). The model never decides
([ADR-0021](adr/0021-ai-suggests-people-decide.md)).

## Money and approvals (phase 6)

```mermaid
flowchart LR
  REQ["adjuster: request payment<br/>Idempotency-Key"] --> LOCK["lock exposure row<br/>available = reserve − paid − committed"]
  LOCK -- "over available" --> NO["422 INSUFFICIENT_RESERVE"]
  LOCK -- "within my limit" --> APP["APPROVED"]
  LOCK -- "above my limit" --> PEND["PENDING_APPROVAL<br/>+ approval request"]
  PEND --> SUP{"supervisor: not the requester,<br/>limit covers it, no SIU hold"}
  SUP -- approve --> APP
  SUP -- reject --> REJ["REJECTED: reserve freed"]
  APP --> JOB["ISSUE_PAYMENT job<br/>rail call outside any transaction"]
  JOB -- refused --> FAILED["FAILED: reserve freed"]
  JOB -- "timeout: maybe paid" --> JOB
  JOB -- ok --> ISSUED["ISSUED + paid on exposure<br/>+ audit + PAYMENT_ISSUED (outbox)"]
```

The claim module asks `ClaimFinancialsPort` whether it may close or be withdrawn, and financials
implements it. A denial is proposed by the adjuster and applied by the approving supervisor
([ADR-0024](adr/0024-financials-exposures-reserves-payments-and-maker-checker.md)). Concurrent payments
on one exposure are serialised by a row lock ([ADR-0025](adr/0025-pessimistic-lock-on-the-exposure-for-payments.md)).

## SIU and activities (phase 7)

```mermaid
flowchart LR
  subgraph Producers["modules publish what they did (outbox)"]
    C["claim: CLAIM_ASSIGNED, HIGH_FRAUD_SCORE,<br/>INFO_REQUEST_OVERDUE / EXPIRED, status changes"]
    S["siu: SIU_CASE_OPENED / DECIDED"]
    F["financials: PAYMENT_STUCK / ISSUED / FAILED"]
  end
  C & S & F --> P["ActivityPlanner<br/>(outbox listener)"]
  P --> A[("activity<br/>person or role queue, due time")]
  A --> D["ACTIVITY_DUE job<br/>at the due time"]
  D -- "still open" --> E["escalate once: URGENT,<br/>SLA_BREACHED, supervisor dashboard"]
  R["refer-siu / triage rule"] --> SC["siu_case OPEN<br/>claim SIU_REVIEW, payments held"]
  SC --> O{"SIU outcome"}
  O -- CLEARED --> OPEN["claim OPEN"]
  O -- CONFIRMED --> DEN["claim OPEN + denial<br/>proposed; supervisor decides"]
```

No module calls the activity module: tasks follow from events
([ADR-0027](adr/0027-activities-from-events-with-sla-timers.md)). The claim module defines
`SiuReferralPort` and SIU implements it; investigators see only claims with a case
([ADR-0026](adr/0026-siu-cases-behind-a-port-with-case-based-visibility.md)).

## Web app (phase 8)

```mermaid
flowchart LR
  subgraph SPA["Angular 21 · Vercel"]
    G["guards per role"] --> F["features: portal, staff workspace,<br/>approvals, activities, SIU"]
    F --> C["typed clients<br/>(types generated from api.v1.json)"]
    C --> I["interceptor: token + correlation ID<br/>401 → single-flight refresh → retry"]
  end
  I -- "REST · If-Match · Idempotency-Key" --> API["API · Render"]
  F -. "presigned PUT (no token)" .-> S[("Storage")]
```

The UI renders the server's `allowedActions` and sends back the ETag it read. It holds the access token
in memory and the refresh token in sessionStorage
([ADR-0029](adr/0029-spa-token-handling-and-a-typed-client-from-the-contract.md)). It calls the API
directly with CORS ([ADR-0030](adr/0030-deployment-topology-and-build-time-api-url.md)).

## Key decisions

See the [ADR index](adr/README.md). The most important:
[database state machine instead of Camunda](adr/0006-database-state-machine-instead-of-a-workflow-engine.md),
[JWT with rotating refresh tokens](adr/0004-jwt-access-tokens-and-rotating-refresh-tokens.md) and
[free-tier hosting](adr/0007-free-tier-hosting.md).
