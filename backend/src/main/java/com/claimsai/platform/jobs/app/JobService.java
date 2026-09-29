package com.claimsai.platform.jobs.app;

import com.claimsai.common.correlation.CorrelationId;
import com.claimsai.common.error.ConflictException;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.platform.jobs.domain.Job;
import com.claimsai.platform.jobs.infra.JobRepository;
import com.claimsai.platform.jobs.infra.JobStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Scheduling and operating jobs (ADR-0016). */
@Service
public class JobService {

    private final JobStore store;
    private final JobRepository jobs;
    private final JobProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JobService(JobStore store, JobRepository jobs, JobProperties properties, ObjectMapper objectMapper,
                      Clock clock) {
        this.store = store;
        this.jobs = jobs;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * A job to schedule.
     *
     * @param delay    zero for "as soon as possible"; a timer otherwise
     * @param dedupKey the same logical job is scheduled once, however often this is called
     */
    public record JobRequest(String type, Long claimId, Map<String, Object> payload, Duration delay,
                             String dedupKey) {

        public static JobRequest now(String type, Long claimId, String dedupKey) {
            return new JobRequest(type, claimId, Map.of(), Duration.ZERO, dedupKey);
        }

        public static JobRequest after(Duration delay, String type, Long claimId, String dedupKey) {
            return new JobRequest(type, claimId, Map.of(), delay, dedupKey);
        }
    }

    /**
     * MANDATORY: the job commits with the business change that needs it, or not at all. A state change
     * whose follow-up work was lost is exactly the dual-write failure this design avoids.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean schedule(JobRequest request) {
        Instant now = clock.instant();
        return store.insertIfAbsent(request.type(), json(request.payload()), request.claimId(),
                properties.maxAttempts(), now.plus(request.delay()), request.dedupKey(), CorrelationId.current(), now);
    }

    /** Cancel a pending job or timer (e.g. the claimant answered before the reminder was due). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancel(String dedupKey) {
        return store.cancelPending(dedupKey, clock.instant()) > 0;
    }

    // ---- operations ----

    @Transactional(readOnly = true)
    public Page<Job> byStatus(Job.Status status, Pageable page) {
        return jobs.findByStatus(status, page);
    }

    @Transactional
    public Job retry(Long jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> new NotFoundException("JOB_NOT_FOUND", "Job " + jobId + " not found"));
        if (job.getStatus() != Job.Status.FAILED) {
            throw new ConflictException("JOB_NOT_FAILED", "Only FAILED jobs can be retried; job " + jobId + " is "
                    + job.getStatus());
        }
        job.retryNow(clock.instant());
        return job;
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Job payload is not serialisable", e);
        }
    }
}
