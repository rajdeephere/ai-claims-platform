package com.claimsai.financials.domain;

import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The maker-checker record: someone asked for something above their authority (or a denial, which always
 * needs a checker), and someone else decides. Decided once; the decider can never be the requester (checked
 * here and by a database constraint).
 */
@Entity
@Table(name = "approval_request")
public class ApprovalRequest {

    public enum Kind { PAYMENT, RESERVE_CHANGE, DENIAL }

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private Kind kind;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason", length = 2000)
    private String decisionReason;

    @Version
    private long version;

    protected ApprovalRequest() {
        // for JPA
    }

    public static ApprovalRequest pending(Long claimId, Kind kind, Long targetId, BigDecimal amount, String reason,
                                          Long requestedBy, Instant now) {
        ApprovalRequest r = new ApprovalRequest();
        r.claimId = claimId;
        r.kind = kind;
        r.targetId = targetId;
        r.amount = amount == null ? null : Money.of(amount);
        r.reason = reason;
        r.requestedBy = requestedBy;
        r.requestedAt = now;
        r.status = Status.PENDING;
        return r;
    }

    public void approve(Long approverId, String comment, Instant now) {
        decide(approverId, Status.APPROVED, comment, now);
    }

    public void reject(Long approverId, String comment, Instant now) {
        if (comment == null || comment.isBlank()) {
            throw new BusinessRuleException("REASON_REQUIRED", "A rejection needs a reason");
        }
        decide(approverId, Status.REJECTED, comment, now);
    }

    public void cancel(Instant now) {
        requirePending();
        status = Status.CANCELLED;
        decidedAt = now;
    }

    private void decide(Long approverId, Status decision, String comment, Instant now) {
        requirePending();
        if (approverId.equals(requestedBy)) {
            throw new BusinessRuleException("SELF_APPROVAL", "You can't decide on your own request");
        }
        status = decision;
        decidedBy = approverId;
        decidedAt = now;
        decisionReason = comment;
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw new ConflictException("ALREADY_DECIDED", "Approval request " + id + " is already " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Kind getKind() {
        return kind;
    }

    public Long getTargetId() {
        return targetId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Status getStatus() {
        return status;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Long getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }
}
