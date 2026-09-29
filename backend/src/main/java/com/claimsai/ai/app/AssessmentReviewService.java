package com.claimsai.ai.app;

import com.claimsai.ai.domain.AiAssessment;
import com.claimsai.ai.domain.ExtractionJson;
import com.claimsai.ai.domain.ExtractionValidator;
import com.claimsai.ai.infra.AiAssessmentRepository;
import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.app.ClaimAccess;
import com.claimsai.claim.app.intake.ClaimIntakeService;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.InvalidTransitionException;
import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.ConflictException;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The human side of "the AI suggests, a person decides". An adjuster (assigned) or supervisor accepts the
 * AI's reading of a document or corrects it; a correction needs a reason, is validated by the same rules as
 * the model's answer, is audited with before and after, and re-scores the claim.
 */
@Service
public class AssessmentReviewService {

    private final AiAssessmentRepository assessments;
    private final ClaimAccess claimAccess;
    private final AuditService audit;
    private final JobService jobs;
    private final Clock clock;

    public AssessmentReviewService(AiAssessmentRepository assessments, ClaimAccess claimAccess, AuditService audit,
                                   JobService jobs, Clock clock) {
        this.assessments = assessments;
        this.claimAccess = claimAccess;
        this.audit = audit;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AiAssessment> forClaim(Long claimId, CurrentUser user) {
        Claim claim = claimAccess.loadVisible(claimId, user);
        return assessments.findByClaimIdOrderByCreatedAtAscIdAsc(claim.getId());
    }

    @Transactional
    public AiAssessment accept(Long assessmentId, CurrentUser user) {
        AiAssessment assessment = loadReviewable(assessmentId, user);
        assessment.accept(user.id(), clock.instant());
        audit.record("AI_ASSESSMENT", assessment.getId(), assessment.getClaimId(), "AI_ASSESSMENT_ACCEPTED",
                actor(user), null, Map.of("assessmentId", assessment.getId()), null);
        return assessment;
    }

    @Transactional
    public AiAssessment override(Long assessmentId, Map<String, Object> corrections, String reason, CurrentUser user) {
        AiAssessment assessment = loadReviewable(assessmentId, user);
        if (corrections == null || corrections.isEmpty()) {
            throw new BusinessRuleException("INVALID_OVERRIDE", "Give at least one corrected field");
        }
        List<String> unknown = corrections.keySet().stream().filter(k -> !ExtractionJson.OVERRIDABLE.contains(k)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("INVALID_OVERRIDE", "These fields can't be overridden: " + unknown
                    + "; allowed: " + ExtractionJson.OVERRIDABLE);
        }
        ExtractionValidator.Result corrected = ExtractionJson.effective(assessment.getOutput(), corrections);
        if (!corrected.valid()) {
            throw new BusinessRuleException("INVALID_OVERRIDE", "The correction is invalid: "
                    + String.join("; ", corrected.errors()));
        }
        Map<String, Object> before = new HashMap<>();
        Map<String, Object> previous = assessment.getReviewStatus() == AiAssessment.ReviewStatus.OVERRIDDEN
                ? assessment.getOverrideOutput() : null;
        corrections.keySet().forEach(k -> before.put(k, previous != null && previous.containsKey(k)
                ? previous.get(k) : valueOf(assessment.getOutput(), k)));

        assessment.override(new LinkedHashMap<>(corrections), reason, user.id(), clock.instant());
        audit.record("AI_ASSESSMENT", assessment.getId(), assessment.getClaimId(), "AI_ASSESSMENT_OVERRIDDEN",
                actor(user), before, new LinkedHashMap<>(corrections), reason);
        // the claim's score now uses the corrected values
        jobs.schedule(JobRequest.now(ClaimIntakeService.REVIEW_CLAIM_RISK, assessment.getClaimId(),
                ClaimIntakeService.REVIEW_CLAIM_RISK + ":" + assessment.getClaimId() + ":override-" + assessment.getId()
                        + "-" + clock.millis()));
        return assessment;
    }

    private AiAssessment loadReviewable(Long assessmentId, CurrentUser user) {
        NotFoundException notFound = new NotFoundException("AI_ASSESSMENT_NOT_FOUND", "Assessment " + assessmentId
                + " not found");
        AiAssessment assessment = assessments.findById(assessmentId).orElseThrow(() -> notFound);
        Claim claim;
        try {
            claim = claimAccess.loadVisible(assessment.getClaimId(), user);
        } catch (NotFoundException hidden) {
            throw notFound;
        }
        claimAccess.requirePermission(ClaimAction.REVIEW_AI, claim, user);
        if (!ClaimAction.REVIEW_AI.availableIn(claim.getStatus())) {
            throw new InvalidTransitionException("review AI results on", claim.getStatus());
        }
        if (!assessment.isReviewable()) {
            throw new ConflictException("NOT_REVIEWABLE", "Only completed document assessments can be reviewed");
        }
        return assessment;
    }

    @SuppressWarnings("unchecked")
    private static Object valueOf(Map<String, Object> output, String key) {
        return switch (key) {
            case "docType" -> output.get("docType");
            case "severity", "costLow", "costHigh" -> output.get("damage") instanceof Map<?, ?> d
                    ? ((Map<String, Object>) d).get(key) : null;
            default -> output.get("fields") instanceof Map<?, ?> f ? ((Map<String, Object>) f).get(key) : null;
        };
    }

    private static AuditActor actor(CurrentUser user) {
        return new AuditActor(user.id(), user.username());
    }
}
