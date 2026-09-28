# ADR-0006: A database state machine and job queue instead of a workflow engine

- **Status:** Accepted (implemented in phases 2 and 3)
- **Date:** 2026-09-28
- **Phase:** 1

## Context

The claims process is long-running: it waits for AI results, claimant replies (days), approvals and SLA
timers. A comparable system (Zynteq) runs it on Camunda 8 / Zeebe. Reviewing that system showed:

- two sources of truth (process variables and the claim table) that drift apart
- dual writes (database, then Zeebe) with no outbox, so a failure between them is lost
- human work modelled as service jobs held open for 24 hours
- a string-typed contract between BPMN variables and Java code, with no tests

Camunda 8 also needs a separate cluster, and self-managed production use needs a licence from 8.6 on.

## Decision

Model the workflow in the application and its database:

| Engine feature | Replacement |
|---|---|
| process state | `claim.status` plus the statuses of payments, exposures, activities, documents |
| gateways | guards in domain methods (`claim.close()`, `payment.approve()`) |
| service tasks | rows in a `job` table, claimed with `FOR UPDATE SKIP LOCKED`, retried with backoff |
| timers | jobs with a future `due_at`: they survive restarts and Render's sleep |
| user tasks | `activity` rows with assignee, candidate role and due date |
| message correlation | an API call or event that loads the claim and applies a transition |
| DMN | a triage decision table in code, thresholds in configuration |

Each transition runs in one database transaction that writes the new state, the audit row, follow-up
jobs and outbox events together.

## Consequences

- ✅ One source of truth and no dual writes: state, audit and follow-up work commit or roll back together.
- ✅ Every rule is plain Java, unit-testable without an engine.
- ✅ Nothing extra to host on the free tier.
- ⚠️ No visual process model or Operate-style console: the audit timeline and an ops view replace them.
- ⚠️ Retries, leases and timers are our code and need their own tests (phase 3).

## Alternatives considered

- **Camunda 8:** see Context.
- **Flowable (embedded, Apache 2.0):** keeps BPMN and shares our transaction; the best choice if the
  business must edit process diagrams. Not needed for a fixed v1 flow.
- **Temporal:** durable workflows as code, but a separate cluster to run.
- **Spring Statemachine:** not durable by default and lightly maintained.
