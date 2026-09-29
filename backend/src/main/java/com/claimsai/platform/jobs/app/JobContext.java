package com.claimsai.platform.jobs.app;

import com.claimsai.platform.jobs.domain.Job;

import java.util.Map;

/** What a handler gets: an immutable view of the job, not the entity. */
public record JobContext(Long id, String type, Long claimId, Map<String, Object> payload, int attempt,
                         int maxAttempts, String correlationId) {

    static JobContext of(Job job) {
        return new JobContext(job.getId(), job.getType(), job.getClaimId(), Map.copyOf(job.getPayload()),
                job.getAttempts(), job.getMaxAttempts(), job.getCorrelationId());
    }

    public boolean isLastAttempt() {
        return attempt >= maxAttempts;
    }
}
