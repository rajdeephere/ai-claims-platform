package com.claimsai.platform.jobs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * A unit of background work, or a timer (a job due later). Inserted and picked up with SQL (JobStore) for
 * SKIP LOCKED and ON CONFLICT; the retry decisions below are plain Java so they are unit-testable.
 */
@Entity
@Table(name = "job")
public class Job {

    public enum Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "claim_id")
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "dedup_key", length = 150)
    private String dedupKey;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Job() {
        // for JPA
    }

    public boolean isLeasedBy(String worker) {
        return status == Status.RUNNING && worker.equals(lockedBy);
    }

    /** Retry later, or give up if this was the last attempt (or the failure is permanent). */
    public void recordFailure(String error, boolean permanent, Instant retryAt, Instant now) {
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        lockedBy = null;
        lockedUntil = null;
        updatedAt = now;
        if (permanent || attempts >= maxAttempts) {
            status = Status.FAILED;
            completedAt = now;
        } else {
            status = Status.PENDING;
            dueAt = retryAt;
        }
    }

    /** An operator retries a dead job: a fresh set of attempts, due now. */
    public void retryNow(Instant now) {
        if (status != Status.FAILED) {
            throw new IllegalStateException("Only FAILED jobs can be retried; job " + id + " is " + status);
        }
        status = Status.PENDING;
        attempts = 0;
        dueAt = now;
        completedAt = null;
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
