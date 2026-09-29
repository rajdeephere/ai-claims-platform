package com.claimsai.ai.domain;

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
import java.util.Map;

/**
 * One AI result and its provenance: which model and prompt version produced it, from which exact file
 * (hash), and what a person decided about it. The AI suggests; {@link #accept} and {@link #override}
 * record the human decision, and the override (with its reason) wins wherever the result is used.
 */
@Entity
@Table(name = "ai_assessment")
public class AiAssessment {

    public enum Kind { DOCUMENT_EXTRACTION, FRAUD_SCORE }

    public enum Status { COMPLETED, FAILED }

    public enum ReviewStatus { PENDING_REVIEW, ACCEPTED, OVERRIDDEN, NOT_APPLICABLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(name = "document_id", updatable = false)
    private Long documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private Status status;

    @Column(updatable = false, length = 100)
    private String model;

    @Column(name = "prompt_version", updatable = false, length = 40)
    private String promptVersion;

    @Column(name = "input_sha256", updatable = false, length = 64)
    private String inputSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> output;

    @Column(updatable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(updatable = false, length = 500)
    private String error;

    @Column(name = "tokens_in", updatable = false)
    private Integer tokensIn;

    @Column(name = "tokens_out", updatable = false)
    private Integer tokensOut;

    @Column(name = "latency_ms", updatable = false)
    private Integer latencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 15)
    private ReviewStatus reviewStatus;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "override_output", columnDefinition = "jsonb")
    private Map<String, Object> overrideOutput;

    @Column(name = "override_reason", length = 2000)
    private String overrideReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected AiAssessment() {
        // for JPA
    }

    public record Provenance(String model, String promptVersion, String inputSha256, Integer tokensIn,
                             Integer tokensOut, Integer latencyMs) {
    }

    public static AiAssessment extraction(Long claimId, Long documentId, Map<String, Object> output, double confidence,
                                          Provenance provenance, Instant now) {
        AiAssessment a = base(claimId, documentId, Kind.DOCUMENT_EXTRACTION, Status.COMPLETED, provenance, now);
        a.output = output;
        a.confidence = BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
        a.reviewStatus = ReviewStatus.PENDING_REVIEW;
        return a;
    }

    public static AiAssessment failedExtraction(Long claimId, Long documentId, String error, Provenance provenance,
                                                Instant now) {
        AiAssessment a = base(claimId, documentId, Kind.DOCUMENT_EXTRACTION, Status.FAILED, provenance, now);
        a.error = error.length() > 500 ? error.substring(0, 500) : error;
        a.reviewStatus = ReviewStatus.NOT_APPLICABLE;
        return a;
    }

    /** Rules-based, so there is nothing for a person to accept: the reasons are the explanation. */
    public static AiAssessment fraudScore(Long claimId, Map<String, Object> output, Instant now) {
        AiAssessment a = base(claimId, null, Kind.FRAUD_SCORE, Status.COMPLETED,
                new Provenance("rules+llm-signals", "fraud-v1", null, null, null, null), now);
        a.output = output;
        a.reviewStatus = ReviewStatus.NOT_APPLICABLE;
        return a;
    }

    private static AiAssessment base(Long claimId, Long documentId, Kind kind, Status status, Provenance p, Instant now) {
        AiAssessment a = new AiAssessment();
        a.claimId = claimId;
        a.documentId = documentId;
        a.kind = kind;
        a.status = status;
        a.model = p.model();
        a.promptVersion = p.promptVersion();
        a.inputSha256 = p.inputSha256();
        a.tokensIn = p.tokensIn();
        a.tokensOut = p.tokensOut();
        a.latencyMs = p.latencyMs();
        a.createdAt = now;
        return a;
    }

    public boolean isReviewable() {
        return kind == Kind.DOCUMENT_EXTRACTION && status == Status.COMPLETED;
    }

    public void accept(Long userId, Instant now) {
        requireReviewable();
        reviewStatus = ReviewStatus.ACCEPTED;
        reviewedBy = userId;
        reviewedAt = now;
        overrideOutput = null;
        overrideReason = null;
    }

    public void override(Map<String, Object> corrected, String reason, Long userId, Instant now) {
        requireReviewable();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An override needs a reason");
        }
        reviewStatus = ReviewStatus.OVERRIDDEN;
        overrideOutput = corrected;
        overrideReason = reason;
        reviewedBy = userId;
        reviewedAt = now;
    }

    private void requireReviewable() {
        if (!isReviewable()) {
            throw new IllegalStateException("Assessment " + id + " (" + kind + ", " + status + ") can't be reviewed");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Kind getKind() {
        return kind;
    }

    public Status getStatus() {
        return status;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getInputSha256() {
        return inputSha256;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getError() {
        return error;
    }

    public Integer getTokensIn() {
        return tokensIn;
    }

    public Integer getTokensOut() {
        return tokensOut;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Map<String, Object> getOverrideOutput() {
        return overrideOutput;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
