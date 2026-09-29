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
 * One claimant x one coverage inside a claim (Guidewire's exposure), e.g. "vehicle damage, insured". It
 * holds the reserve: the money set aside for what the insurer expects to pay. Payments draw on it; the
 * database refuses paid > reserve whatever code tries.
 */
@Entity
@Table(name = "exposure")
public class Exposure {

    public enum Type { VEHICLE_DAMAGE, PROPERTY_DAMAGE, BODILY_INJURY }

    public enum Status { OPEN, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Type type;

    @Column(name = "coverage_type", nullable = false, updatable = false, length = 20)
    private String coverageType;

    @Column(name = "claimant_name", nullable = false, updatable = false, length = 100)
    private String claimantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "reserve_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal reserveAmount;

    @Column(name = "paid_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidAmount;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected Exposure() {
        // for JPA
    }

    /** New exposures start with no reserve; setting it is a separate, authority-checked step. */
    public static Exposure open(Long claimId, Type type, String coverageType, String claimantName, Long createdBy,
                                Instant now) {
        Exposure e = new Exposure();
        e.claimId = claimId;
        e.type = type;
        e.coverageType = coverageType;
        e.claimantName = claimantName;
        e.createdBy = createdBy;
        e.createdAt = now;
        e.status = Status.OPEN;
        e.reserveAmount = Money.ZERO;
        e.paidAmount = Money.ZERO;
        return e;
    }

    public void setReserve(BigDecimal newReserve) {
        requireOpen();
        BigDecimal amount = Money.of(newReserve);
        if (amount.compareTo(paidAmount) < 0) {
            throw new BusinessRuleException("RESERVE_BELOW_PAID",
                    "The reserve can't be lower than what was already paid (" + paidAmount + ")");
        }
        reserveAmount = amount;
    }

    /** What can still be paid: reserve, minus paid, minus payments already approved or awaiting approval. */
    public BigDecimal available(BigDecimal committedToPendingPayments) {
        return reserveAmount.subtract(paidAmount).subtract(committedToPendingPayments);
    }

    public void recordPayment(BigDecimal amount) {
        BigDecimal newPaid = paidAmount.add(Money.of(amount));
        if (newPaid.compareTo(reserveAmount) > 0) {
            throw new IllegalStateException("Payment would exceed the reserve of exposure " + id);
        }
        paidAmount = newPaid;
    }

    /** Closing releases what's left of the reserve: nothing more is expected to be paid. */
    public void close(Instant now) {
        requireOpen();
        reserveAmount = paidAmount;
        status = Status.CLOSED;
        closedAt = now;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new ConflictException("EXPOSURE_CLOSED", "Exposure " + id + " is closed");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Type getType() {
        return type;
    }

    public String getCoverageType() {
        return coverageType;
    }

    public String getClaimantName() {
        return claimantName;
    }

    public Status getStatus() {
        return status;
    }

    public BigDecimal getReserveAmount() {
        return reserveAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getVersion() {
        return version;
    }
}
