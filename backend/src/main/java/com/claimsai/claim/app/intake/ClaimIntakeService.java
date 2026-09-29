package com.claimsai.claim.app.intake;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.app.AssignmentService;
import com.claimsai.claim.app.ClaimAuditTrail;
import com.claimsai.claim.app.TriageProperties;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimEnums.PolicyVerification;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.PolicyCheck;
import com.claimsai.claim.domain.RiskAssessmentPort;
import com.claimsai.claim.domain.RiskAssessmentPort.ClaimRiskFacts;
import com.claimsai.claim.domain.RiskAssessmentPort.RiskAssessment;
import com.claimsai.claim.domain.TriageRules;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import com.claimsai.policy.domain.PolicySnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The system's steps on a new claim, run as jobs (ADR-0018, ADR-0021):
 * <pre>
 * FNOL ── VERIFY_POLICY ──> ASSESSING ── (document grace) ── COMPLETE_ASSESSMENT ──> OPEN / SIU_REVIEW
 *                                 │                            │ waits while documents are being assessed
 *                                 └── ASSESSMENT_TIMEOUT (timer): moves on without the missing assessments
 * </pre>
 * COMPLETE_ASSESSMENT scores the claim (rules + AI signals), triages it with the score and assigns it.
 * Every step re-reads the claim and does nothing if it has already moved on.
 */
@Service
public class ClaimIntakeService {

    public static final String VERIFY_POLICY = "VERIFY_POLICY";
    public static final String COMPLETE_ASSESSMENT = "COMPLETE_ASSESSMENT";
    public static final String ASSESSMENT_TIMEOUT = "ASSESSMENT_TIMEOUT";
    /** Scheduled by the ai module after each document assessment. */
    public static final String REVIEW_CLAIM_RISK = "REVIEW_CLAIM_RISK";

    private final ClaimRepository claims;
    private final AssignmentService assignment;
    private final ClaimAuditTrail auditTrail;
    private final JobService jobs;
    private final RiskAssessmentPort risk;
    private final TriageProperties triage;
    private final Duration assessmentTimeout;
    private final Duration documentGrace;
    private final Duration recheckInterval;
    private final Clock clock;

    public ClaimIntakeService(ClaimRepository claims, AssignmentService assignment, ClaimAuditTrail auditTrail,
                              JobService jobs, RiskAssessmentPort risk, TriageProperties triage,
                              @Value("${app.intake.assessment-timeout}") Duration assessmentTimeout,
                              @Value("${app.intake.document-grace}") Duration documentGrace,
                              @Value("${app.intake.assessment-recheck}") Duration recheckInterval, Clock clock) {
        this.claims = claims;
        this.assignment = assignment;
        this.auditTrail = auditTrail;
        this.jobs = jobs;
        this.risk = risk;
        this.triage = triage;
        this.assessmentTimeout = assessmentTimeout;
        this.documentGrace = documentGrace;
        this.recheckInterval = recheckInterval;
        this.clock = clock;
    }

    static String key(String type, Long claimId) {
        return type + ":" + claimId;
    }

    /** Called in the FNOL transaction: the claim and its first job commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void start(Claim claim) {
        jobs.schedule(JobRequest.now(VERIFY_POLICY, claim.getId(), key(VERIFY_POLICY, claim.getId())));
    }

    /** SUBMITTED -> ASSESSING with the policy result; schedules the assessment and its timeout. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyPolicyCheck(Long claimId, Optional<PolicySnapshot> policy) {
        Claim claim = claims.findById(claimId).orElseThrow();
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            return;   // already done by an earlier run of this job
        }
        Instant now = clock.instant();
        PolicyCheck.Result result = PolicyCheck.check(policy, claim.getLossType(), claim.getLossDate(),
                claim.getClaimantUserId());
        var transition = claim.startAssessment(result, policy.map(PolicySnapshot::effectiveFrom).orElse(null), now);
        auditTrail.event(claim, "POLICY_CHECKED", AuditActor.SYSTEM, null, Map.of(
                "verification", claim.getPolicyVerification().name(),
                "flags", result.flags().stream().map(Enum::name).sorted().toList()), null);
        auditTrail.transition(claim, transition, AuditActor.SYSTEM, null);

        // a short grace period: the FNOL form uploads its documents right after submitting
        jobs.schedule(JobRequest.after(documentGrace, COMPLETE_ASSESSMENT, claimId, key(COMPLETE_ASSESSMENT, claimId)));
        jobs.schedule(JobRequest.after(assessmentTimeout, ASSESSMENT_TIMEOUT, claimId, key(ASSESSMENT_TIMEOUT, claimId)));
    }

    /**
     * ASSESSING -> OPEN (or SIU_REVIEW): score, triage, assign. While documents are still being assessed it
     * checks again later instead; the timeout timer guarantees that "later" ends.
     *
     * @param timedOut true when the timeout timer fired: move on with whatever assessments exist
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeAssessment(Long claimId, boolean timedOut) {
        Claim claim = claims.findById(claimId).orElseThrow();
        if (claim.getStatus() != ClaimStatus.ASSESSING) {
            return;
        }
        Instant now = clock.instant();
        if (!timedOut && risk.assessmentPending(claimId)) {
            // a fresh key per re-check: the dedup key of the first run is already used
            jobs.schedule(JobRequest.after(recheckInterval, COMPLETE_ASSESSMENT, claimId,
                    key(COMPLETE_ASSESSMENT, claimId) + ":" + now.toEpochMilli()));
            return;
        }
        if (timedOut) {
            claim.flagAssessmentTimedOut(now);
            auditTrail.event(claim, "ASSESSMENT_TIMED_OUT", AuditActor.SYSTEM, null, null,
                    "assessment not finished within " + assessmentTimeout);
        } else {
            jobs.cancel(key(ASSESSMENT_TIMEOUT, claimId));
        }

        RiskAssessment assessment = score(claim, now, "FRAUD_SCORED");
        BigDecimal estimate = claim.getEstimatedLoss() != null ? claim.getEstimatedLoss() : assessment.assessedAmount();
        TriageRules.Decision decision = TriageRules.decide(new TriageRules.Input(estimate, claim.isInjuriesReported(),
                claim.getPolicyVerification() == PolicyVerification.VERIFIED, claim.getFraudScore()), triage.thresholds());
        var transition = claim.completeAssessment(decision, now);
        Map<String, Object> triaged = new HashMap<>();
        triaged.put("segment", decision.segment().name());
        triaged.put("referToSiu", decision.referToSiu());
        auditTrail.event(claim, "CLAIM_TRIAGED", AuditActor.SYSTEM, null, triaged, decision.reason());
        auditTrail.transition(claim, transition, AuditActor.SYSTEM, null);

        Optional<Long> adjuster = assignment.leastLoadedAdjuster();
        if (adjuster.isPresent()) {
            claim.assignTo(adjuster.get(), now);
            auditTrail.event(claim, "CLAIM_ASSIGNED", AuditActor.SYSTEM, null, Map.of("adjusterId", adjuster.get()),
                    "least open claims");
        } else {
            claim.markUnassigned(now);
            auditTrail.event(claim, "CLAIM_UNASSIGNED", AuditActor.SYSTEM, null, null, "no active adjuster");
        }
    }

    /**
     * A document was assessed. During ASSESSING: re-check completion now instead of at the next interval.
     * Afterwards: re-score, and flag the claim if it crossed the SIU threshold (referral is a person's call).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reviewRisk(Long claimId) {
        Claim claim = claims.findById(claimId).orElseThrow();
        Instant now = clock.instant();
        if (claim.getStatus() == ClaimStatus.ASSESSING) {
            jobs.schedule(JobRequest.now(COMPLETE_ASSESSMENT, claimId,
                    key(COMPLETE_ASSESSMENT, claimId) + ":" + now.toEpochMilli()));
            return;
        }
        if (!claim.getStatus().isActive() || claim.getStatus() == ClaimStatus.SUBMITTED) {
            return;
        }
        Integer before = claim.getFraudScore();
        score(claim, now, "FRAUD_SCORE_UPDATED");
        int threshold = triage.siuReferralScore();
        if (claim.getFraudScore() >= threshold && (before == null || before < threshold)
                && claim.getStatus() != ClaimStatus.SIU_REVIEW) {
            claim.flagHighFraudScore(now);
            auditTrail.event(claim, "HIGH_FRAUD_SCORE", AuditActor.SYSTEM, null,
                    Map.of("fraudScore", claim.getFraudScore()), "score reached the SIU threshold " + threshold);
        }
    }

    private RiskAssessment score(Claim claim, Instant now, String auditAction) {
        long otherClaims = claims.countByPolicyNumberAndIdNotAndLossDateBetween(claim.getPolicyNumber(), claim.getId(),
                claim.getLossDate().minusYears(1), claim.getLossDate());
        RiskAssessment assessment = risk.assess(new ClaimRiskFacts(claim.getId(), claim.getPolicyNumber(),
                claim.getLossDate(), claim.getPolicyStart(), claim.getEstimatedLoss(), otherClaims));
        Integer previous = claim.recordFraudScore(assessment.fraudScore(), now);
        Map<String, Object> before = new HashMap<>();
        before.put("fraudScore", previous);
        auditTrail.event(claim, auditAction, AuditActor.SYSTEM, before, Map.of("fraudScore", assessment.fraudScore(),
                "reasons", assessment.reasons()), null);
        return assessment;
    }
}
