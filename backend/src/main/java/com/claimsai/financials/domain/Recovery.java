package com.claimsai.financials.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Money coming back: subrogation from the at-fault party or their insurer, or salvage. Recorded, not edited. */
@Entity
@Immutable
@Table(name = "recovery")
public class Recovery {

    public enum Source { THIRD_PARTY_INSURER, THIRD_PARTY, SALVAGE, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Source source;

    @Column(length = 100)
    private String reference;

    @Column(name = "received_on", nullable = false)
    private LocalDate receivedOn;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Recovery() {
        // for JPA
    }

    public Recovery(Long claimId, BigDecimal amount, Source source, String reference, LocalDate receivedOn,
                    Long recordedBy, Instant createdAt) {
        this.claimId = claimId;
        this.amount = Money.of(amount);
        this.source = source;
        this.reference = reference;
        this.receivedOn = receivedOn;
        this.recordedBy = recordedBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Source getSource() {
        return source;
    }

    public String getReference() {
        return reference;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public Long getRecordedBy() {
        return recordedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
