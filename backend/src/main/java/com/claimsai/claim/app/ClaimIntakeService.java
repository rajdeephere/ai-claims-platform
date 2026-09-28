package com.claimsai.claim.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.PolicyCheck;
import com.claimsai.claim.domain.TriageRules;
import com.claimsai.policy.domain.PolicyPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The system's first steps on a new claim: policy check, triage, assignment.
 *
 * <p>Phase 2 runs them inside the FNOL transaction. That's safe only because the policy "system" is a
 * stub in our own database: one transaction, nothing remote. Phase 3 moves each step to a job, because a
 * real policy system is a network call that must neither hold a database transaction open nor be lost on
 * failure (ADR-0014). Phase 5 adds the AI assessment between the policy check and triage.
 */
@Service
public class ClaimIntakeService {

    private final PolicyPort policies;
    private final AssignmentService assignment;
    private final ClaimAuditTrail auditTrail;
    private final TriageProperties triage;
    private final Clock clock;

    public ClaimIntakeService(PolicyPort policies, AssignmentService assignment, ClaimAuditTrail auditTrail,
                              TriageProperties triage, Clock clock) {
        this.policies = policies;
        this.assignment = assignment;
        this.auditTrail = auditTrail;
        this.triage = triage;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void process(Claim claim) {
        AuditActor system = AuditActor.SYSTEM;
        Instant now = clock.instant();

        PolicyCheck.Result policyCheck = PolicyCheck.check(policies.findPolicy(claim.getPolicyNumber()),
                claim.getLossType(), claim.getLossDate(), claim.getClaimantUserId());
        var toAssessing = claim.startAssessment(policyCheck, now);
        auditTrail.event(claim, "POLICY_CHECKED", system, null, Map.of(
                "verification", claim.getPolicyVerification().name(),
                "flags", policyCheck.flags().stream().map(Enum::name).sorted().toList()), null);
        auditTrail.transition(claim, toAssessing, system, null);

        TriageRules.Decision decision = TriageRules.decide(new TriageRules.Input(claim.getEstimatedLoss(),
                claim.isInjuriesReported(), policyCheck.verified(), claim.getFraudScore()), triage.thresholds());
        var toOpen = claim.completeAssessment(decision, now);
        Map<String, Object> triaged = new HashMap<>();
        triaged.put("segment", decision.segment().name());
        triaged.put("referToSiu", decision.referToSiu());
        auditTrail.event(claim, "CLAIM_TRIAGED", system, null, triaged, decision.reason());
        auditTrail.transition(claim, toOpen, system, null);

        Optional<Long> adjuster = assignment.leastLoadedAdjuster();
        if (adjuster.isPresent()) {
            claim.assignTo(adjuster.get(), now);
            auditTrail.event(claim, "CLAIM_ASSIGNED", system, null, Map.of("adjusterId", adjuster.get()),
                    "least open claims");
        } else {
            // no adjuster available: the claim waits for a supervisor instead of failing the FNOL
            claim.markUnassigned(now);
            auditTrail.event(claim, "CLAIM_UNASSIGNED", system, null, null, "no active adjuster");
        }
    }
}
