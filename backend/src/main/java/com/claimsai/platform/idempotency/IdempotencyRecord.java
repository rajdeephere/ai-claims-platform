package com.claimsai.platform.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.time.Instant;

/**
 * "This user already sent this Idempotency-Key for this operation; it created resource X." Written in the
 * same transaction as the resource, so there is never a resource without its record or the reverse.
 *
 * <p>{@link Persistable#isNew()} is always true: the id is assigned by the caller, and without this Spring
 * Data would call {@code merge} (SELECT, then INSERT). {@code persist} goes straight to INSERT, so two
 * concurrent requests with the same key collide on the primary key instead of both passing a SELECT.
 */
@Entity
@IdClass(IdempotencyRecord.Key.class)
@Table(name = "idempotency_record")
public class IdempotencyRecord implements Persistable<IdempotencyRecord.Key> {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    @Column(nullable = false, length = 40)
    private String operation;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private final boolean isNew = true;

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(Long userId, String idempotencyKey, String operation, String requestHash,
                             Long resourceId, Instant createdAt) {
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.requestHash = requestHash;
        this.resourceId = resourceId;
        this.createdAt = createdAt;
    }

    @Override
    public Key getId() {
        return new Key(userId, idempotencyKey);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getOperation() {
        return operation;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public record Key(Long userId, String idempotencyKey) implements Serializable {
    }
}
