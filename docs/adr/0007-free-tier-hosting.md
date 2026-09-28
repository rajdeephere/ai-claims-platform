# ADR-0007: Free-tier hosting on Vercel, Render and Supabase

- **Status:** Accepted
- **Date:** 2026-09-28
- **Phase:** 1

## Context

The project must be deployed and demonstrable at no cost and without a payment card. Vercel serves
static sites and short serverless functions; it can't run a long-lived JVM with background jobs.

## Decision

| Part | Host | Constraint designed for |
|---|---|---|
| Angular UI | Vercel | none |
| Spring Boot API (Docker) | Render free web service | 512 MB RAM; sleeps after 15 min idle; cold start about a minute |
| PostgreSQL + document storage | Supabase | project pauses after about 7 days without activity; direct connection is IPv6-only |
| LLM | Groq free tier | rate limits |

Rules that follow:

- Every setting comes from environment variables; one image runs locally and in the cloud.
- The JVM is sized for 512 MB (`MaxRAMPercentage=75`, SerialGC, 512 KB thread stacks); the Hikari pool
  is 5. Measured in phase 1: 268 MiB used under a 512 MB limit.
- All timers and jobs are database rows with a due time, never in memory, so work resumes after the
  container wakes.
- The database is reached through Supabase's Supavisor pooler (session mode, port 5432).
- A pinger (UptimeRobot / cron-job.org) calls `/actuator/health` every 10 minutes.
- Only standard protocols (JDBC, S3 API, OpenAI-compatible HTTP), behind ports, so any vendor can be
  replaced by configuration.

## Consequences

- ✅ Free, public, and close to a real deployment (containers, managed Postgres, object storage).
- ⚠️ Cold starts: open the site a couple of minutes before a demo.
- ⚠️ No Kafka: free hosted brokers are gone. A transactional outbox gives the same guarantees in-process.

## Alternatives considered

- **Oracle Cloud Always Free VM / Google Cloud Run:** more resources, but both need a card.
- **Hugging Face Spaces (Docker):** 16 GB RAM with no card; kept as the fallback if 512 MB is too small.
- **Neon + Cloudflare R2:** fine, but R2 may ask for a card and it adds a vendor.
