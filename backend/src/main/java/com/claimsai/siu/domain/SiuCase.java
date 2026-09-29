package com.claimsai.siu.domain;

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

import java.time.Instant;

/**
 * An investigation by the Special Investigations Unit. Opened by the triage rule (score at or above the
 * threshold) or by a person; decided once, by an investigator, with findings. At most one is open per
 * claim (partial unique index).
 */
@Entity
@Table(name = "siu_case")
public class SiuCase {

    public enum Source { RULE, MANUAL }

    public enum Status { OPEN, CLEARED, CONFIRMED }

    /** What an investigator can conclude. */
    public enum Outcome {
        /** No fraud found: normal handling resumes. */
        CLEARED,
        /** Fraud confirmed: the claim goes back to handling with a denial proposed to a supervisor. */
        CONFIRMED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private Source source;

    @Column(nullable = false, updatable = false, length = 2000)
    private String reason;

    @Column(name = "referred_by", updatable = false)
    private Long referredBy;

    @Column(name = "referred_at", nullable = false, updatable = false)
    private Instant referredAt;

    @Column(name = "fraud_score_at_referral", updatable = false)
    private Integer fraudScoreAtReferral;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "investigator_id")
    private Long investigatorId;

    @Column(length = 4000)
    private String findings;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    private long version;

    protected SiuCase() {
        // for JPA
    }

    /** @param referredBy null for the triage rule; required for a manual referral */
    public static SiuCase open(Long claimId, Source source, String reason, Long referredBy, Integer fraudScore,
                               Instant now) {
        if ((source == Source.MANUAL) != (referredBy != null)) {
            throw new IllegalArgumentException("A manual referral has a referrer, a rule referral has none");
        }
        SiuCase c = new SiuCase();
        c.claimId = claimId;
        c.source = source;
        c.reason = reason;
        c.referredBy = referredBy;
        c.fraudScoreAtReferral = fraudScore;
        c.referredAt = now;
        c.status = Status.OPEN;
        return c;
    }

    public void decide(Outcome outcome, Long investigatorId, String findings, Instant now) {
        if (status != Status.OPEN) {
            throw new ConflictException("ALREADY_DECIDED", "SIU case " + id + " is already " + status);
        }
        if (findings == null || findings.isBlank()) {
            throw new BusinessRuleException("FINDINGS_REQUIRED", "An SIU outcome needs findings");
        }
        this.status = outcome == Outcome.CLEARED ? Status.CLEARED : Status.CONFIRMED;
        this.investigatorId = investigatorId;
        this.findings = findings;
        this.decidedAt = now;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Source getSource() {
        return source;
    }

    public String getReason() {
        return reason;
    }

    public Long getReferredBy() {
        return referredBy;
    }

    public Instant getReferredAt() {
        return referredAt;
    }

    public Integer getFraudScoreAtReferral() {
        return fraudScoreAtReferral;
    }

    public Status getStatus() {
        return status;
    }

    public Long getInvestigatorId() {
        return investigatorId;
    }

    public String getFindings() {
        return findings;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public long getVersion() {
        return version;
    }
}
