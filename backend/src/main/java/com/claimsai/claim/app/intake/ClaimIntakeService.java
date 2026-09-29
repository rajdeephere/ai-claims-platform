package com.claimsai.claim.app.intake;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.app.AssignmentService;
import com.claimsai.claim.app.ClaimAuditTrail;
import com.claimsai.claim.app.TriageProperties;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimEnums.PolicyVerification;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.PolicyCheck;
import com.claimsai.claim.domain.TriageRules;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import com.claimsai.policy.domain.PolicySnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The system's steps on a new claim, run as jobs (ADR-0018, superseding ADR-0014):
 * <pre>
 * FNOL commit ── VERIFY_POLICY ──> SUBMITTED -> ASSESSING ── COMPLETE_ASSESSMENT ──> OPEN / SIU_REVIEW
 *                                              └─ ASSESSMENT_TIMEOUT (timer, safety net) ──┘
 * </pre>
 * Every step re-reads the claim and does nothing if it has already moved on, so a step that runs twice
 * (retry, lease takeover) is harmless. Each step schedules the next one in its own transaction: the state
 * change and the follow-up work commit together, so the chain can't break between steps.
 */
@Service
public class ClaimIntakeService {

    public static final String VERIFY_POLICY = "VERIFY_POLICY";
    public static final String COMPLETE_ASSESSMENT = "COMPLETE_ASSESSMENT";
    public static final String ASSESSMENT_TIMEOUT = "ASSESSMENT_TIMEOUT";

    private final ClaimRepository claims;
    private final AssignmentService assignment;
    private final ClaimAuditTrail auditTrail;
    private final JobService jobs;
    private final TriageProperties triage;
    private final Duration assessmentTimeout;
    private final Clock clock;

    public ClaimIntakeService(ClaimRepository claims, AssignmentService assignment, ClaimAuditTrail auditTrail,
                              JobService jobs, TriageProperties triage,
                              @Value("${app.intake.assessment-timeout}") Duration assessmentTimeout, Clock clock) {
        this.claims = claims;
        this.assignment = assignment;
        this.auditTrail = auditTrail;
        this.jobs = jobs;
        this.triage = triage;
        this.assessmentTimeout = assessmentTimeout;
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
        var transition = claim.startAssessment(result, now);
        auditTrail.event(claim, "POLICY_CHECKED", AuditActor.SYSTEM, null, Map.of(
                "verification", claim.getPolicyVerification().name(),
                "flags", result.flags().stream().map(Enum::name).sorted().toList()), null);
        auditTrail.transition(claim, transition, AuditActor.SYSTEM, null);

        // Phase 5 inserts the AI assessment here; COMPLETE_ASSESSMENT will then wait for it.
        jobs.schedule(JobRequest.now(COMPLETE_ASSESSMENT, claimId, key(COMPLETE_ASSESSMENT, claimId)));
        jobs.schedule(JobRequest.after(assessmentTimeout, ASSESSMENT_TIMEOUT, claimId, key(ASSESSMENT_TIMEOUT, claimId)));
    }

    /**
     * ASSESSING -> OPEN (or SIU_REVIEW): triage and assignment.
     *
     * @param timedOut true when the timeout timer fired first: the claim moves on, flagged
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeAssessment(Long claimId, boolean timedOut) {
        Claim claim = claims.findById(claimId).orElseThrow();
        if (claim.getStatus() != ClaimStatus.ASSESSING) {
            return;
        }
        Instant now = clock.instant();
        if (timedOut) {
            claim.flagAssessmentTimedOut(now);
            auditTrail.event(claim, "ASSESSMENT_TIMED_OUT", AuditActor.SYSTEM, null, null,
                    "assessment not finished within " + assessmentTimeout);
        } else {
            jobs.cancel(key(ASSESSMENT_TIMEOUT, claimId));
        }

        TriageRules.Decision decision = TriageRules.decide(new TriageRules.Input(claim.getEstimatedLoss(),
                claim.isInjuriesReported(), claim.getPolicyVerification() == PolicyVerification.VERIFIED,
                claim.getFraudScore()), triage.thresholds());
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
}
