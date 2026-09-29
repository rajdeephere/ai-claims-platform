package com.claimsai.platform.jobs.app;

import com.claimsai.common.correlation.CorrelationId;
import com.claimsai.platform.jobs.domain.Job;
import com.claimsai.platform.jobs.infra.JobRepository;
import com.claimsai.platform.jobs.infra.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Polls the job table and runs due jobs (ADR-0016). One pass:
 * <ol>
 *   <li>short transaction: claim a batch (SKIP LOCKED), counting the attempt and taking a lease</li>
 *   <li>per job: run the handler, then mark DONE; the same transaction for transactional handlers</li>
 *   <li>on failure: a new transaction records it (retry with backoff, or FAILED)</li>
 * </ol>
 * Nothing is kept in memory between passes, so a restart, a crash or Render putting the container to
 * sleep loses nothing: due jobs and expired leases are simply picked up by the next pass.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobStore store;
    private final JobRepository jobs;
    private final JobProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final String workerId;

    public JobRunner(JobStore store, JobRepository jobs, JobProperties properties, List<JobHandler> handlerBeans,
                     PlatformTransactionManager transactionManager, Clock clock) {
        this.store = store;
        this.jobs = jobs;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        for (JobHandler handler : handlerBeans) {
            if (handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("Two handlers for job type " + handler.type());
            }
        }
        this.workerId = hostName() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-interval-ms:2000}")
    public void poll() {
        if (properties.enabled()) {
            runDueJobs();
        }
    }

    /** One pass; returns how many jobs it ran. Public so tests and ops can trigger a pass. */
    public int runDueJobs() {
        Instant now = clock.instant();
        List<Long> claimed = transaction.execute(status -> {
            store.failExhaustedLeases(now);
            return store.claimBatch(workerId, now, now.plus(properties.lease()), properties.batchSize());
        });
        claimed.forEach(this::run);
        return claimed.size();
    }

    private void run(Long jobId) {
        JobContext job = transaction.execute(status -> JobContext.of(jobs.findById(jobId).orElseThrow()));
        String previousCorrelation = MDC.get(CorrelationId.MDC_KEY);
        // the job's log lines and audit entries carry the ID of the request that caused it
        MDC.put(CorrelationId.MDC_KEY, job.correlationId() != null ? job.correlationId() : "job-" + jobId);
        MDC.put("jobId", String.valueOf(jobId));
        try {
            JobHandler handler = handlers.get(job.type());
            if (handler == null) {
                throw new PermanentJobFailure("No handler for job type " + job.type());
            }
            if (handler.transactional()) {
                transaction.executeWithoutResult(status -> {
                    handler.handle(job);
                    markDone(job);
                });
            } else {
                handler.handle(job);
                transaction.executeWithoutResult(status -> markDone(job));
            }
            log.debug("Job {} {} done (attempt {})", jobId, job.type(), job.attempt());
        } catch (RuntimeException e) {
            recordFailure(job, e);
        } finally {
            MDC.remove("jobId");
            if (previousCorrelation == null) {
                MDC.remove(CorrelationId.MDC_KEY);
            } else {
                MDC.put(CorrelationId.MDC_KEY, previousCorrelation);
            }
        }
    }

    /**
     * If our lease expired and another worker took the job, it's theirs now. For a transactional handler,
     * throwing here rolls our work back, so the job's effect happens once.
     */
    private void markDone(JobContext job) {
        if (store.markDone(job.id(), workerId, clock.instant()) == 0) {
            throw new LeaseLostException(job.id());
        }
    }

    private void recordFailure(JobContext job, RuntimeException e) {
        if (e instanceof LeaseLostException) {
            log.warn("Job {} {}: lease lost to another worker, result discarded", job.id(), job.type());
            return;
        }
        boolean permanent = e instanceof PermanentJobFailure;
        transaction.executeWithoutResult(status -> jobs.findById(job.id())
                .filter(j -> j.isLeasedBy(workerId))
                .ifPresent(j -> {
                    Instant now = clock.instant();
                    j.recordFailure(e.getClass().getSimpleName() + ": " + e.getMessage(), permanent,
                            now.plus(properties.backoffAfter(job.attempt())), now);
                    if (j.getStatus() == Job.Status.FAILED) {
                        log.error("Job {} {} FAILED after {} attempt(s): {}", job.id(), job.type(), job.attempt(),
                                e.getMessage(), e);
                    } else {
                        log.warn("Job {} {} attempt {} failed, retry at {}: {}", job.id(), job.type(),
                                job.attempt(), j.getDueAt(), e.getMessage());
                    }
                }));
    }

    public String workerId() {
        return workerId;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }

    static final class LeaseLostException extends RuntimeException {
        LeaseLostException(Long jobId) {
            super("Lease on job " + jobId + " was taken over by another worker");
        }
    }
}
