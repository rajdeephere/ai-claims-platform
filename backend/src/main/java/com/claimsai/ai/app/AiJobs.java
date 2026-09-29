package com.claimsai.ai.app;

import com.claimsai.document.domain.DocumentEvents;
import com.claimsai.platform.jobs.app.JobContext;
import com.claimsai.platform.jobs.app.JobHandler;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import com.claimsai.platform.outbox.app.OutboxListener;
import com.claimsai.platform.outbox.app.OutboxMessage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** How documents get to the AI: an outbox listener turns each verified upload into an ASSESS_DOCUMENT job. */
public final class AiJobs {

    private AiJobs() {
    }

    /** The listener only schedules (cheap, in its own transaction); the slow work is the job's. */
    @Component
    public static class DocumentUploadedListener implements OutboxListener {

        private final JobService jobs;

        public DocumentUploadedListener(JobService jobs) {
            this.jobs = jobs;
        }

        @Override
        public String name() {
            return "ai-document-assessment";
        }

        @Override
        public Set<String> eventTypes() {
            return Set.of(DocumentEvents.DOCUMENT_UPLOADED);
        }

        @Override
        public void on(OutboxMessage event) {
            Long documentId = event.number(DocumentEvents.DOCUMENT_ID);
            jobs.schedule(new JobRequest(DocumentAssessmentService.JOB_TYPE, event.number(DocumentEvents.CLAIM_ID),
                    Map.of("documentId", documentId), Duration.ZERO,
                    DocumentAssessmentService.JOB_TYPE + ":" + documentId));
        }
    }

    /** Remote call inside: not transactional (the service opens its own short transactions). */
    @Component
    public static class AssessDocument implements JobHandler {

        private final DocumentAssessmentService service;

        public AssessDocument(DocumentAssessmentService service) {
            this.service = service;
        }

        @Override
        public String type() {
            return DocumentAssessmentService.JOB_TYPE;
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public void handle(JobContext job) {
            service.assess(job);
        }
    }
}
