package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.ClaimFlag;
import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;
import com.claimsai.claim.domain.ClaimEnums.PolicyVerification;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.common.error.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The claim aggregate: one loss event on one policy. There are no setters: every change is a domain method
 * that checks the move is allowed (ClaimStatus) and any business guard, so no code path can put a claim in
 * a state the lifecycle doesn't allow.
 */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_number", nullable = false, updatable = false, length = 20)
    private String claimNumber;

    @Column(name = "policy_number", nullable = false, updatable = false, length = 20)
    private String policyNumber;

    @Column(name = "claimant_user_id", updatable = false)
    private Long claimantUserId;

    @Column(name = "reported_by_user_id", nullable = false, updatable = false)
    private Long reportedByUserId;

    @Column(name = "contact_name", nullable = false, length = 100)
    private String contactName;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "loss_date", nullable = false, updatable = false)
    private LocalDate lossDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "loss_type", nullable = false, updatable = false, length = 20)
    private LossType lossType;

    @Column(name = "loss_location", nullable = false, length = 200)
    private String lossLocation;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "injuries_reported", nullable = false)
    private boolean injuriesReported;

    @Column(name = "estimated_loss", precision = 14, scale = 2)
    private BigDecimal estimatedLoss;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private ClaimStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_outcome", length = 12)
    private CloseOutcome closeOutcome;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private Segment segment;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_verification", nullable = false, length = 12)
    private PolicyVerification policyVerification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> flags = new ArrayList<>();

    @Column(name = "fraud_score")
    private Integer fraudScore;

    /** The policy's start date at the policy check (fraud signal: loss soon after inception). */
    @Column(name = "policy_start")
    private LocalDate policyStart;

    @Column(name = "assigned_adjuster_id")
    private Long assignedAdjusterId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /** Optimistic lock: a concurrent change makes the second commit fail instead of overwriting (409). */
    @Version
    private long version;

    protected Claim() {
        // for JPA
    }

    /** What the reporter tells us at first notice of loss. */
    public record LossReport(String policyNumber, LocalDate lossDate, LossType lossType, String lossLocation,
                             String description, boolean injuriesReported, BigDecimal estimatedLoss,
                             String contactName, String contactPhone) {
    }

    /** FNOL: a new claim in SUBMITTED. */
    public static Claim submit(String claimNumber, LossReport report, Long claimantUserId, Long reportedByUserId,
                               LocalDate today, Instant now) {
        if (report.lossDate().isAfter(today)) {
            throw new BusinessRuleException("LOSS_DATE_IN_FUTURE", "The loss date can't be in the future");
        }
        Claim claim = new Claim();
        claim.claimNumber = claimNumber;
        claim.policyNumber = report.policyNumber();
        claim.claimantUserId = claimantUserId;
        claim.reportedByUserId = reportedByUserId;
        claim.contactName = report.contactName();
        claim.contactPhone = report.contactPhone();
        claim.lossDate = report.lossDate();
        claim.lossType = report.lossType();
        claim.lossLocation = report.lossLocation();
        claim.description = report.description();
        claim.injuriesReported = report.injuriesReported();
        claim.estimatedLoss = report.estimatedLoss() == null ? null
                : report.estimatedLoss().setScale(2, RoundingMode.UNNECESSARY);
        claim.status = ClaimStatus.SUBMITTED;
        claim.policyVerification = PolicyVerification.PENDING;
        claim.createdAt = now;
        claim.updatedAt = now;
        return claim;
    }

    // ---- system steps ----

    /** SUBMITTED -> ASSESSING, with the policy check result. */
    public ClaimTransition startAssessment(PolicyCheck.Result policyCheck, LocalDate policyStart, Instant now) {
        ClaimTransition transition = moveTo(ClaimStatus.ASSESSING, "start assessment", now);
        this.policyStart = policyStart;
        policyVerification = policyCheck.verified() ? PolicyVerification.VERIFIED : PolicyVerification.UNVERIFIED;
        addFlags(policyCheck.flags());
        return transition;
    }

    /** ASSESSING -> OPEN, or SIU_REVIEW when triage refers it. */
    public ClaimTransition completeAssessment(TriageRules.Decision decision, Instant now) {
        ClaimTransition transition = moveTo(decision.referToSiu() ? ClaimStatus.SIU_REVIEW : ClaimStatus.OPEN,
                "complete assessment", now);
        segment = decision.segment();
        return transition;
    }

    /** Assign or reassign. Returns the previous adjuster (may be null). */
    public Long assignTo(Long adjusterId, Instant now) {
        if (!status.isActive()) {
            throw new InvalidTransitionException("assign", status);
        }
        Long previous = assignedAdjusterId;
        assignedAdjusterId = Objects.requireNonNull(adjusterId);
        removeFlag(ClaimFlag.UNASSIGNED);
        updatedAt = now;
        return previous;
    }

    public void flagAssessmentTimedOut(Instant now) {
        if (status != ClaimStatus.ASSESSING) {
            throw new InvalidTransitionException("time out the assessment of", status);
        }
        addFlags(EnumSet.of(ClaimFlag.ASSESSMENT_TIMED_OUT));
        updatedAt = now;
    }

    /** The risk assessment's score, 0-100; replaced when new documents change it. */
    public Integer recordFraudScore(int score, Instant now) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Fraud score must be 0-100, was " + score);
        }
        if (!status.isActive()) {
            throw new InvalidTransitionException("score", status);
        }
        Integer previous = fraudScore;
        fraudScore = score;
        updatedAt = now;
        return previous;
    }

    public void flagHighFraudScore(Instant now) {
        addFlags(EnumSet.of(ClaimFlag.HIGH_FRAUD_SCORE));
        updatedAt = now;
    }

    public void markUnassigned(Instant now) {
        addFlags(EnumSet.of(ClaimFlag.UNASSIGNED));
        updatedAt = now;
    }

    // ---- handling ----

    public ClaimTransition requestInformation(Instant now) {
        return moveTo(ClaimStatus.AWAITING_INFO, "request information on", now);
    }

    public ClaimTransition informationReceived(Instant now) {
        return moveTo(ClaimStatus.OPEN, "receive information on", now);
    }

    /** The adjuster cancels their own request (e.g. the answer came by phone). */
    public ClaimTransition informationRequestCancelled(Instant now) {
        return moveTo(ClaimStatus.OPEN, "cancel the information request on", now);
    }

    /** Nobody answered in time: the claim comes back to the adjuster, who decides what to do. */
    public ClaimTransition informationRequestExpired(Instant now) {
        return moveTo(ClaimStatus.OPEN, "expire the information request on", now);
    }

    /** OPEN -> SIU_REVIEW. A pending "high fraud score" flag has now been acted on. */
    public ClaimTransition referToSiu(Instant now) {
        ClaimTransition transition = moveTo(ClaimStatus.SIU_REVIEW, "refer to SIU", now);
        removeFlag(ClaimFlag.HIGH_FRAUD_SCORE);
        return transition;
    }

    public ClaimTransition siuReviewCompleted(Instant now) {
        return moveTo(ClaimStatus.OPEN, "complete the SIU review of", now);
    }

    /**
     * OPEN -> CLOSED as PAID or NO_PAYMENT. Only from OPEN: a claim waiting for the claimant must first come
     * back (or be withdrawn). Exposure and payment guards are added with the money module (phase 6).
     */
    public ClaimTransition close(boolean anyPaymentIssued, Instant now) {
        requireStatus(ClaimStatus.OPEN, "close");
        return closeAs(anyPaymentIssued ? CloseOutcome.PAID : CloseOutcome.NO_PAYMENT, "close", now);
    }

    /** Only through an approved denial request (maker-checker, phase 6). */
    public ClaimTransition deny(Instant now) {
        requireStatus(ClaimStatus.OPEN, "deny");
        return closeAs(CloseOutcome.DENIED, "deny", now);
    }

    /** The claimant withdraws: from OPEN or AWAITING_INFO, never once money has gone out. */
    public ClaimTransition withdraw(boolean anyPaymentIssued, Instant now) {
        if (anyPaymentIssued) {
            throw new BusinessRuleException("PAYMENT_ALREADY_ISSUED", "A claim with payments can't be withdrawn");
        }
        if (status != ClaimStatus.OPEN && status != ClaimStatus.AWAITING_INFO) {
            throw new InvalidTransitionException("withdraw", status);
        }
        return closeAs(CloseOutcome.WITHDRAWN, "withdraw", now);
    }

    public ClaimTransition reopen(Instant now) {
        ClaimTransition transition = moveTo(ClaimStatus.OPEN, "reopen", now);
        closeOutcome = null;
        closedAt = null;
        return transition;
    }

    // ---- internals ----

    private ClaimTransition closeAs(CloseOutcome outcome, String action, Instant now) {
        ClaimTransition transition = moveTo(ClaimStatus.CLOSED, action, now);
        closeOutcome = outcome;
        closedAt = now;
        return transition;
    }

    private void requireStatus(ClaimStatus expected, String action) {
        if (status != expected) {
            throw new InvalidTransitionException(action, status);
        }
    }

    private ClaimTransition moveTo(ClaimStatus target, String action, Instant now) {
        if (!status.canMoveTo(target)) {
            throw new InvalidTransitionException(action, status);
        }
        ClaimStatus from = status;
        status = target;
        updatedAt = now;
        return new ClaimTransition(id, from, target, action);
    }

    /** Always a new list: Hibernate then sees a changed value, not a list mutated behind its back. */
    private void addFlags(Collection<ClaimFlag> newFlags) {
        List<String> updated = new ArrayList<>(flags);
        for (ClaimFlag flag : newFlags) {
            if (!updated.contains(flag.name())) {
                updated.add(flag.name());
            }
        }
        flags = updated;
    }

    private void removeFlag(ClaimFlag flag) {
        List<String> updated = new ArrayList<>(flags);
        updated.remove(flag.name());
        flags = updated;
    }

    // ---- read access ----

    public boolean isAssignedTo(Long userId) {
        return userId != null && userId.equals(assignedAdjusterId);
    }

    public boolean isFiledBy(Long userId) {
        return userId != null && userId.equals(claimantUserId);
    }

    public Set<ClaimFlag> getFlags() {
        Set<ClaimFlag> result = EnumSet.noneOf(ClaimFlag.class);
        flags.forEach(f -> result.add(ClaimFlag.valueOf(f)));
        return result;
    }

    public Long getId() {
        return id;
    }

    public String getClaimNumber() {
        return claimNumber;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public Long getClaimantUserId() {
        return claimantUserId;
    }

    public Long getReportedByUserId() {
        return reportedByUserId;
    }

    public String getContactName() {
        return contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public LocalDate getLossDate() {
        return lossDate;
    }

    public LossType getLossType() {
        return lossType;
    }

    public String getLossLocation() {
        return lossLocation;
    }

    public String getDescription() {
        return description;
    }

    public boolean isInjuriesReported() {
        return injuriesReported;
    }

    public BigDecimal getEstimatedLoss() {
        return estimatedLoss;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public CloseOutcome getCloseOutcome() {
        return closeOutcome;
    }

    public Segment getSegment() {
        return segment;
    }

    public PolicyVerification getPolicyVerification() {
        return policyVerification;
    }

    public Integer getFraudScore() {
        return fraudScore;
    }

    public LocalDate getPolicyStart() {
        return policyStart;
    }

    public Long getAssignedAdjusterId() {
        return assignedAdjusterId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getVersion() {
        return version;
    }
}
