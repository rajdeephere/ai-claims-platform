package com.claimsai.ai.app;

import com.claimsai.ai.domain.AiAssessment;
import com.claimsai.ai.domain.AiAssessment.Provenance;
import com.claimsai.ai.domain.ExtractionJson;
import com.claimsai.ai.domain.LlmClient.LlmRejectedException;
import com.claimsai.ai.domain.LlmClient.LlmUnavailableException;
import com.claimsai.ai.domain.UnreadableDocumentException;
import com.claimsai.ai.infra.AiAssessmentRepository;
import com.claimsai.ai.infra.DocumentInputPreparer;
import com.claimsai.ai.infra.DocumentInputPreparer.PreparedInput;
import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.claim.app.ClaimQueryService.ClaimFacts;
import com.claimsai.claim.app.intake.ClaimIntakeService;
import com.claimsai.document.app.DocumentProcessing;
import com.claimsai.document.app.DocumentProcessing.DocumentFacts;
import com.claimsai.document.domain.Document;
import com.claimsai.platform.jobs.app.JobContext;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import com.claimsai.platform.jobs.app.PermanentJobFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Map;

/**
 * The ASSESS_DOCUMENT job (ADR-0021). Everything slow or remote (reading the file, calling the model)
 * happens outside any transaction; only the result is written, in one short transaction:
 * assessment + document status + audit + the claim's risk review job.
 *
 * <p>Failure policy: a temporary LLM problem (timeout, 5xx, rate limit) is retried by the job queue with
 * backoff. On the last attempt, or for a permanent problem (unreadable file, request refused, invalid answer
 * twice), a FAILED assessment is recorded and the document marked FAILED: the adjuster reviews it by hand
 * and the claim is never stuck.
 */
@Service
public class DocumentAssessmentService {

    public static final String JOB_TYPE = "ASSESS_DOCUMENT";

    private final DocumentProcessing documents;
    private final ClaimQueryService claims;
    private final DocumentInputPreparer preparer;
    private final ExtractionService extraction;
    private final AiAssessmentRepository assessments;
    private final AuditService audit;
    private final JobService jobs;
    private final AiProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DocumentAssessmentService(DocumentProcessing documents, ClaimQueryService claims,
                                     DocumentInputPreparer preparer, ExtractionService extraction,
                                     AiAssessmentRepository assessments, AuditService audit, JobService jobs,
                                     AiProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.documents = documents;
        this.claims = claims;
        this.preparer = preparer;
        this.extraction = extraction;
        this.assessments = assessments;
        this.audit = audit;
        this.jobs = jobs;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void assess(JobContext job) {
        Long documentId = ((Number) job.payload().get("documentId")).longValue();
        DocumentFacts document = documents.facts(documentId)
                .orElseThrow(() -> new PermanentJobFailure("Document " + documentId + " does not exist"));
        if (document.status() == Document.Status.PROCESSED || document.status() == Document.Status.REJECTED
                || document.status() == Document.Status.PENDING_UPLOAD) {
            return;   // done by an earlier run, or nothing to assess
        }
        if (assessments.existsByDocumentIdAndKindAndStatus(documentId, AiAssessment.Kind.DOCUMENT_EXTRACTION,
                AiAssessment.Status.COMPLETED)) {
            return;   // an earlier run stored its result and crashed before the job was marked done
        }
        ClaimFacts claim = claims.facts(document.claimId());
        documents.markProcessing(documentId);

        ExtractionService.Outcome outcome;
        try {
            PreparedInput input = preparer.prepare(documents.content(document), document.contentType(),
                    properties.maxDocumentChars());
            outcome = extraction.extract(input, claim, document.category().name());
        } catch (LlmUnavailableException e) {
            if (!job.isLastAttempt()) {
                throw e;   // the queue retries with backoff
            }
            recordFailure(document, "AI unavailable after " + job.attempt() + " attempts: " + e.getMessage(), null);
            return;
        } catch (LlmRejectedException | UnreadableDocumentException e) {
            recordFailure(document, e.getMessage(), null);
            return;
        }

        Provenance provenance = new Provenance(outcome.model(), outcome.promptVersion(), document.sha256(),
                outcome.tokensIn(), outcome.tokensOut(), outcome.latencyMs());
        if (!outcome.valid()) {
            recordFailure(document, "invalid AI answer: " + String.join("; ", outcome.errors()), provenance);
            return;
        }
        transaction.executeWithoutResult(status -> {
            AiAssessment saved = assessments.save(AiAssessment.extraction(document.claimId(), documentId,
                    ExtractionJson.toMap(outcome.extraction()), outcome.extraction().confidence(), provenance,
                    clock.instant()));
            documents.markProcessed(documentId, outcome.extraction().docType().name());
            audit.record("DOCUMENT", documentId, document.claimId(), "AI_DOCUMENT_ASSESSED", AuditActor.SYSTEM, null,
                    Map.of("assessmentId", saved.getId(), "docType", outcome.extraction().docType().name(),
                            "model", outcome.model(), "signals", outcome.extraction().riskSignals().size()), null);
            scheduleRiskReview(document);
        });
    }

    private void recordFailure(DocumentFacts document, String error, Provenance provenance) {
        Provenance p = provenance != null ? provenance
                : new Provenance(null, properties.promptVersion(), document.sha256(), null, null, null);
        transaction.executeWithoutResult(status -> {
            assessments.save(AiAssessment.failedExtraction(document.claimId(), document.documentId(), error, p,
                    clock.instant()));
            documents.markFailed(document.documentId());
            audit.record("DOCUMENT", document.documentId(), document.claimId(), "AI_DOCUMENT_FAILED", AuditActor.SYSTEM,
                    null, Map.of("documentId", document.documentId()), error);
            scheduleRiskReview(document);   // the claim must not keep waiting for this document
        });
    }

    private void scheduleRiskReview(DocumentFacts document) {
        jobs.schedule(JobRequest.now(ClaimIntakeService.REVIEW_CLAIM_RISK, document.claimId(),
                ClaimIntakeService.REVIEW_CLAIM_RISK + ":" + document.claimId() + ":doc-" + document.documentId()));
    }
}
