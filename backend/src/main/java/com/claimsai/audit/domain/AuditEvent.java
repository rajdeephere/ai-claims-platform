package com.claimsai.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * One fact in the audit trail: who did what to which entity, the value before and after, and why.
 * {@code @Immutable} stops Hibernate from ever issuing an UPDATE; a database trigger rejects UPDATE and
 * DELETE from anywhere else (ADR-0013).
 */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 30)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "claim_id")
    private Long claimId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", nullable = false, length = 50)
    private String actorName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private Map<String, Object> oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private Map<String, Object> newValue;

    @Column(length = 2000)
    private String reason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
        // for JPA
    }

    public AuditEvent(String entityType, Long entityId, Long claimId, String action, Long actorId, String actorName,
                      Map<String, Object> oldValue, Map<String, Object> newValue, String reason,
                      String correlationId, Instant occurredAt) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.claimId = claimId;
        this.action = action;
        this.actorId = actorId;
        this.actorName = actorName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = reason;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getAction() {
        return action;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorName() {
        return actorName;
    }

    public Map<String, Object> getOldValue() {
        return oldValue;
    }

    public Map<String, Object> getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
