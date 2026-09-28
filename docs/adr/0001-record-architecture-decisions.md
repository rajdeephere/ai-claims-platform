# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

Design choices are easy to forget and expensive to reverse. Reviewers need to know why something was
built a certain way, not only what was built.

## Decision

Record every significant decision as a short ADR in `docs/adr/`, numbered in sequence, using
[`template.md`](template.md). An accepted ADR is not edited; a changed decision gets a new ADR that
supersedes the old one.

## Consequences

- ✅ Decisions and their trade-offs are reviewed with the code, in the same pull request.
- ✅ Superseded ADRs keep the history of how the design evolved.
- ⚠️ A small writing cost per decision.

## Alternatives considered

- **Wiki pages:** drift away from the code and aren't versioned with it.
- **Code comments only:** too local for cross-cutting decisions.
