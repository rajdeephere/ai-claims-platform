package com.claimsai.platform.jobs.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The SQL that makes the table a queue. Plain SQL because JPA can't express SKIP LOCKED with RETURNING or
 * ON CONFLICT on a partial index.
 */
@Component
public class JobStore {

    private final JdbcClient jdbc;

    public JobStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert unless a job with the same dedup key exists. Returns false if it already did. */
    public boolean insertIfAbsent(String type, String payloadJson, Long claimId, int maxAttempts, Instant dueAt,
                                  String dedupKey, String correlationId, Instant now) {
        int inserted = jdbc.sql("""
                        INSERT INTO job (type, payload, claim_id, status, attempts, max_attempts, due_at,
                                         dedup_key, correlation_id, created_at, updated_at)
                        VALUES (:type, CAST(:payload AS jsonb), :claimId, 'PENDING', 0, :maxAttempts, :dueAt,
                                :dedupKey, :correlationId, :now, :now)
                        ON CONFLICT (dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING""")
                .param("type", type)
                .param("payload", payloadJson)
                .param("claimId", claimId)
                .param("maxAttempts", maxAttempts)
                .param("dueAt", Timestamp.from(dueAt))
                .param("dedupKey", dedupKey)
                .param("correlationId", correlationId)
                .param("now", Timestamp.from(now))
                .update();
        return inserted == 1;
    }

    /**
     * Claims up to {@code limit} jobs for this worker: due PENDING jobs, plus RUNNING jobs whose lease
     * expired (their worker crashed or the container slept). {@code FOR UPDATE SKIP LOCKED}: two workers
     * polling at once get disjoint jobs and never wait for each other. The attempt is counted here, at
     * pickup, so a job that crashes its worker every time still runs out of attempts.
     */
    public List<Long> claimBatch(String worker, Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE job SET status = 'RUNNING', locked_by = :worker, locked_until = :leaseUntil,
                                       attempts = attempts + 1, updated_at = :now
                        WHERE id IN (
                            SELECT id FROM job
                            WHERE ((status = 'PENDING' AND due_at <= :now)
                                   OR (status = 'RUNNING' AND locked_until < :now))
                              AND attempts < max_attempts
                            ORDER BY due_at, id
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id""")
                .param("worker", worker)
                .param("now", Timestamp.from(now))
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    /** A job whose final attempt died with its worker would never be picked again: mark it FAILED. */
    public int failExhaustedLeases(Instant now) {
        return jdbc.sql("""
                        UPDATE job SET status = 'FAILED', locked_by = NULL, locked_until = NULL, updated_at = :now,
                                       completed_at = :now,
                                       last_error = 'Lease expired on the final attempt (worker crashed or timed out)'
                        WHERE status = 'RUNNING' AND locked_until < :now AND attempts >= max_attempts""")
                .param("now", Timestamp.from(now))
                .update();
    }

    /** DONE only if this worker still holds the lease; 0 means another worker took the job over. */
    public int markDone(Long id, String worker, Instant now) {
        return jdbc.sql("""
                        UPDATE job SET status = 'DONE', locked_by = NULL, locked_until = NULL, updated_at = :now,
                                       completed_at = :now, last_error = NULL
                        WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker""")
                .param("id", id)
                .param("worker", worker)
                .param("now", Timestamp.from(now))
                .update();
    }

    public int cancelPending(String dedupKey, Instant now) {
        return jdbc.sql("""
                        UPDATE job SET status = 'CANCELLED', updated_at = :now, completed_at = :now
                        WHERE dedup_key = :dedupKey AND status = 'PENDING'""")
                .param("dedupKey", dedupKey)
                .param("now", Timestamp.from(now))
                .update();
    }
}
