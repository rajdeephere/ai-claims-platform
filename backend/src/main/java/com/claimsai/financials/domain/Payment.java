package com.claimsai.financials.domain;

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
 * Money going out against an exposure.
 * <pre>
 * within the requester's authority:  APPROVED ──> ISSUED | FAILED
 * above it:  PENDING_APPROVAL ──> APPROVED ──> ISSUED | FAILED
 *                    └──> REJECTED
 * </pre>
 * The idempotency key is ours, generated once, and sent to the payment rail with every attempt: a retry
 * after a lost response gets the first payment back instead of paying twice.
 */
@Entity
@Table(name = "payment")
public class Payment {

    public enum Status { PENDING_APPROVAL, APPROVED, ISSUED, FAILED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(name = "exposure_id", nullable = false, updatable = false)
    private Long exposureId;

    @Column(nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "payee_name", nullable = false, updatable = false, length = 100)
    private String payeeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approval_request_id")
    private Long approvalRequestId;

    @Column(name = "external_reference", length = 64)
    private String externalReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Version
    private long version;

    protected Payment() {
        // for JPA
    }

    public static Payment requested(Long claimId, Long exposureId, BigDecimal amount, String payeeName,
                                    String idempotencyKey, Long requestedBy, boolean withinRequesterAuthority,
                                    Instant now) {
        Payment p = new Payment();
        p.claimId = claimId;
        p.exposureId = exposureId;
        p.amount = Money.of(amount);
        p.payeeName = payeeName;
        p.idempotencyKey = idempotencyKey;
        p.requestedBy = requestedBy;
        p.createdAt = now;
        if (withinRequesterAuthority) {
            p.status = Status.APPROVED;
            p.approvedBy = requestedBy;   // their own authority covers it: no second person needed
        } else {
            p.status = Status.PENDING_APPROVAL;
        }
        return p;
    }

    public void awaitApproval(Long approvalRequestId) {
        this.approvalRequestId = approvalRequestId;
    }

    public void approvedBy(Long approverId) {
        require(Status.PENDING_APPROVAL);
        status = Status.APPROVED;
        approvedBy = approverId;
    }

    public void rejected() {
        require(Status.PENDING_APPROVAL);
        status = Status.REJECTED;
    }

    public void issued(String reference, Instant now) {
        require(Status.APPROVED);
        status = Status.ISSUED;
        externalReference = reference;
        issuedAt = now;
    }

    public void failed(String reason) {
        require(Status.APPROVED);
        status = Status.FAILED;
        failureReason = reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    /** Still counts against the exposure's available reserve. */
    public boolean isCommitted() {
        return status == Status.PENDING_APPROVAL || status == Status.APPROVED;
    }

    private void require(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("Payment " + id + " is " + status + ", expected " + expected);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getExposureId() {
        return exposureId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getPayeeName() {
        return payeeName;
    }

    public Status getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Long getApprovalRequestId() {
        return approvalRequestId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
