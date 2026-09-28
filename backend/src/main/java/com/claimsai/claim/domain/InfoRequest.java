package com.claimsai.claim.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** An adjuster's question to the claimant. At most one is OPEN per claim (partial unique index). */
@Entity
@Table(name = "info_request")
public class InfoRequest {

    public enum Status { OPEN, ANSWERED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(nullable = false, updatable = false, length = 2000)
    private String message;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 2000)
    private String response;

    @Column(name = "responded_by")
    private Long respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected InfoRequest() {
        // for JPA
    }

    public InfoRequest(Long claimId, String message, Long requestedBy, Instant requestedAt) {
        this.claimId = claimId;
        this.message = message;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.status = Status.OPEN;
    }

    public void answer(String response, Long respondedBy, Instant now) {
        requireOpen();
        this.response = response;
        this.respondedBy = respondedBy;
        this.respondedAt = now;
        this.status = Status.ANSWERED;
    }

    public void cancel(Instant now) {
        requireOpen();
        this.respondedAt = now;
        this.status = Status.CANCELLED;
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new IllegalStateException("Information request " + id + " is " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getMessage() {
        return message;
    }

    public Long getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getResponse() {
        return response;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }
}
