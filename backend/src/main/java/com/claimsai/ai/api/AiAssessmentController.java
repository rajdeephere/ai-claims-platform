package com.claimsai.ai.api;

import com.claimsai.ai.app.AssessmentReviewService;
import com.claimsai.ai.domain.AiAssessment;
import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.identity.app.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** AI results for staff; claimants never see them. */
@RestController
@PreAuthorize("hasAnyRole('ADJUSTER', 'SUPERVISOR', 'SIU')")
@Tag(name = "AI assessments", description = "What the AI read from documents, the fraud score, and human review")
public class AiAssessmentController {

    private final AssessmentReviewService reviews;
    private final CurrentUserProvider currentUser;

    public AiAssessmentController(AssessmentReviewService reviews, CurrentUserProvider currentUser) {
        this.reviews = reviews;
        this.currentUser = currentUser;
    }

    public record AiAssessmentView(Long id, Long claimId, Long documentId, AiAssessment.Kind kind,
                                   AiAssessment.Status status, String model, String promptVersion,
                                   @Schema(description = "SHA-256 of the exact file assessed") String inputSha256,
                                   Map<String, Object> output, BigDecimal confidence, String error,
                                   AiAssessment.ReviewStatus reviewStatus, Long reviewedBy, Instant reviewedAt,
                                   Map<String, Object> overrideOutput, String overrideReason, Integer latencyMs,
                                   Instant createdAt) {

        static AiAssessmentView of(AiAssessment a) {
            return new AiAssessmentView(a.getId(), a.getClaimId(), a.getDocumentId(), a.getKind(), a.getStatus(),
                    a.getModel(), a.getPromptVersion(), a.getInputSha256(), a.getOutput(), a.getConfidence(),
                    a.getError(), a.getReviewStatus(), a.getReviewedBy(), a.getReviewedAt(), a.getOverrideOutput(),
                    a.getOverrideReason(), a.getLatencyMs(), a.getCreatedAt());
        }
    }

    public record OverrideRequest(
            @Schema(description = "Corrected values; allowed keys: docType, totalAmount, currency, issueDate, "
                    + "severity, costLow, costHigh", example = "{\"totalAmount\": 3500.00}")
            @NotEmpty Map<String, Object> fields,
            @NotBlank @Size(max = 2000) String reason) {
    }

    @GetMapping("/api/v1/claims/{claimId}/ai-assessments")
    @Operation(operationId = "listClaimAiAssessments", summary = "Document extractions and fraud scores, oldest first")
    @DocumentedErrors({404})
    public List<AiAssessmentView> list(@PathVariable Long claimId) {
        return reviews.forClaim(claimId, currentUser.get()).stream().map(AiAssessmentView::of).toList();
    }

    @PostMapping("/api/v1/ai-assessments/{id}/accept")
    @Operation(operationId = "acceptAiAssessment", summary = "Confirm the AI's reading of a document")
    @DocumentedErrors({404, 409})
    public AiAssessmentView accept(@PathVariable Long id) {
        return AiAssessmentView.of(reviews.accept(id, currentUser.get()));
    }

    @PostMapping("/api/v1/ai-assessments/{id}/override")
    @Operation(operationId = "overrideAiAssessment", summary = "Correct the AI's reading, with a reason",
            description = "The claim's fraud score is recomputed with the corrected values.")
    @DocumentedErrors({404, 409, 422})
    public AiAssessmentView override(@PathVariable Long id, @Valid @RequestBody OverrideRequest request) {
        return AiAssessmentView.of(reviews.override(id, request.fields(), request.reason(), currentUser.get()));
    }
}
