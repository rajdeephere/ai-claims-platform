package com.claimsai.ai.app;

import com.claimsai.ai.domain.AiAssessment;
import com.claimsai.ai.domain.DocumentExtraction;
import com.claimsai.ai.domain.DocumentExtraction.DocType;
import com.claimsai.ai.domain.ExtractionJson;
import com.claimsai.ai.domain.ExtractionValidator;
import com.claimsai.ai.domain.FraudScorer;
import com.claimsai.ai.infra.AiAssessmentRepository;
import com.claimsai.claim.domain.RiskAssessmentPort;
import com.claimsai.document.app.DocumentProcessing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The claim module's risk port, implemented with the AI results (dependency inversion: claim defines the
 * port, ai implements it). The score uses each document's EFFECTIVE result: a person's override, where
 * there is one, replaces what the model said. The AI suggests; people decide.
 */
@Service
public class ClaimRiskAssessor implements RiskAssessmentPort {

    private final AiAssessmentRepository assessments;
    private final DocumentProcessing documents;
    private final Clock clock;

    public ClaimRiskAssessor(AiAssessmentRepository assessments, DocumentProcessing documents, Clock clock) {
        this.assessments = assessments;
        this.documents = documents;
        this.clock = clock;
    }

    @Override
    public boolean assessmentPending(Long claimId) {
        return documents.hasDocumentsAwaitingAssessment(claimId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RiskAssessment assess(ClaimRiskFacts facts) {
        List<DocumentExtraction> extractions = effectiveExtractions(facts.claimId());
        FraudScorer.Score score = FraudScorer.score(new FraudScorer.Facts(facts.lossDate(), facts.policyStart(),
                facts.otherClaimsOnPolicy(), documents.duplicatesOnOtherClaims(facts.claimId()), facts.estimatedLoss(),
                extractions));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("score", score.value());
        output.put("reasons", score.reasons().stream().map(r -> Map.of("rule", r.rule().name(), "points", r.points(),
                "detail", r.detail())).toList());
        assessments.save(AiAssessment.fraudScore(facts.claimId(), output, clock.instant()));

        List<String> reasons = score.reasons().stream()
                .map(r -> r.rule() + " (+" + r.points() + "): " + r.detail()).toList();
        return new RiskAssessment(score.value(), reasons, assessedAmount(extractions));
    }

    private List<DocumentExtraction> effectiveExtractions(Long claimId) {
        List<DocumentExtraction> result = new ArrayList<>();
        for (AiAssessment a : assessments.findByClaimIdAndKindAndStatus(claimId, AiAssessment.Kind.DOCUMENT_EXTRACTION,
                AiAssessment.Status.COMPLETED)) {
            ExtractionValidator.Result effective = ExtractionJson.effective(a.getOutput(),
                    a.getReviewStatus() == AiAssessment.ReviewStatus.OVERRIDDEN ? a.getOverrideOutput() : null);
            if (effective.valid()) {
                result.add(effective.extraction());
            }
        }
        return result;
    }

    /** The largest amount the documents support: an estimate/invoice total, or the top of a damage range. */
    private static BigDecimal assessedAmount(List<DocumentExtraction> extractions) {
        return extractions.stream()
                .flatMap(e -> Stream.of(
                        e.docType() == DocType.REPAIR_ESTIMATE || e.docType() == DocType.INVOICE ? e.totalAmount() : null,
                        e.damage() == null ? null : e.damage().costHigh()))
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }
}
