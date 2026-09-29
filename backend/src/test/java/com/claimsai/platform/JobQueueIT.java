package com.claimsai.platform;

import com.claimsai.common.web.PageResponse;
import com.claimsai.platform.jobs.api.JobOpsController.JobView;
import com.claimsai.platform.jobs.app.JobRunner;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import com.claimsai.platform.jobs.domain.Job;
import com.claimsai.platform.jobs.infra.JobRepository;
import com.claimsai.platform.jobs.infra.JobStore;
import com.claimsai.support.IntegrationTest;
import com.claimsai.support.TestBackgroundWork;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class JobQueueIT extends IntegrationTest {

    @Autowired
    private JobService jobService;
    @Autowired
    private JobRepository jobs;
    @Autowired
    private JobStore store;
    @Autowired
    private JobRunner runner;

    private Job schedule(String type, Map<String, Object> payload, Duration delay) {
        String key = type + ":" + UUID.randomUUID();
        inTransaction(() -> jobService.schedule(new JobRequest(type, null, payload, delay, key)));
        return jobs.findByDedupKey(key).orElseThrow();
    }

    private Job reload(Job job) {
        return jobs.findById(job.getId()).orElseThrow();
    }

    /** "Time passes": the retry or timer becomes due now. */
    private void makeDue(Job job) {
        jdbc.sql("UPDATE job SET due_at = :now WHERE id = :id")
                .param("now", Timestamp.from(Instant.now().minusSeconds(1))).param("id", job.getId()).update();
    }

    @Test
    void aScheduledJobRunsOnceAndCarriesTheCorrelationIdOfTheRequestThatCausedIt() {
        org.slf4j.MDC.put("correlationId", "job-it-1");
        Job job;
        try {
            job = schedule(TestBackgroundWork.OK, Map.of(), Duration.ZERO);
        } finally {
            org.slf4j.MDC.remove("correlationId");
        }

        eventually().until(() -> reload(job).getStatus() == Job.Status.DONE);
        assertThat(TestBackgroundWork.JOB_RUNS.get(job.getId())).hasValue(1);
        assertThat(TestBackgroundWork.JOB_CORRELATION.get(job.getId())).isEqualTo("job-it-1");
        assertThat(reload(job).getAttempts()).isEqualTo(1);
    }

    @Test
    void aFailingJobIsRetriedWithBackoffUntilItSucceeds() {
        Job job = schedule(TestBackgroundWork.FLAKY, Map.of("failTimes", 2), Duration.ZERO);

        eventually().until(() -> reload(job).getAttempts() == 1 && reload(job).getStatus() == Job.Status.PENDING);
        Job afterFirst = reload(job);
        assertThat(afterFirst.getLastError()).contains("flaky failure on attempt 1");
        // first backoff step is 30 s: not due yet
        assertThat(afterFirst.getDueAt()).isAfter(Instant.now().plusSeconds(20));

        makeDue(job);
        eventually().until(() -> reload(job).getAttempts() == 2 && reload(job).getStatus() == Job.Status.PENDING);
        makeDue(job);
        eventually().until(() -> reload(job).getStatus() == Job.Status.DONE);

        assertThat(reload(job).getAttempts()).isEqualTo(3);
        assertThat(reload(job).getLastError()).isNull();
    }

    @Test
    void aPermanentFailureGoesStraightToFailedAndSupervisorsCanRetryIt() {
        Job job = schedule(TestBackgroundWork.PERMANENT, Map.of(), Duration.ZERO);
        eventually().until(() -> reload(job).getStatus() == Job.Status.FAILED);
        assertThat(reload(job).getAttempts()).isEqualTo(1);
        assertThat(reload(job).getLastError()).contains("cannot ever work");

        PageResponse<JobView> failed = http.exchange("/api/v1/ops/jobs?status=FAILED&size=100", HttpMethod.GET,
                new HttpEntity<>(headers("supervisor1")), new ParameterizedTypeReference<PageResponse<JobView>>() { })
                .getBody();
        assertThat(failed.content()).extracting(JobView::id).contains(job.getId());
        assertThat(get("adjuster1", "/api/v1/ops/jobs", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var retried = post("supervisor1", "/api/v1/ops/jobs/" + job.getId() + "/retry", null, JobView.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        eventually().until(() -> reload(job).getStatus() == Job.Status.FAILED && reload(job).getAttempts() == 1
                && TestBackgroundWork.JOB_RUNS.get(job.getId()).get() == 2);

        var notFailed = post("supervisor1", "/api/v1/ops/jobs/" + schedule(TestBackgroundWork.OK, Map.of(),
                Duration.ofHours(1)).getId() + "/retry", null, String.class);
        assertThat(notFailed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aJobIsScheduledOnlyOncePerDedupKey() {
        String key = "dedup:" + UUID.randomUUID();
        boolean first = inTransaction(() -> jobService.schedule(JobRequest.after(Duration.ofHours(1), TestBackgroundWork.OK, null, key)));
        boolean second = inTransaction(() -> jobService.schedule(JobRequest.after(Duration.ofHours(1), TestBackgroundWork.OK, null, key)));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM job WHERE dedup_key = ?").param(key).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void aTimerWaitsUntilItIsDueAndCanBeCancelled() {
        Job timer = schedule(TestBackgroundWork.OK, Map.of(), Duration.ofHours(24));
        runner.runDueJobs();
        assertThat(reload(timer).getStatus()).isEqualTo(Job.Status.PENDING);

        assertThat(inTransaction(() -> jobService.cancel(timer.getDedupKey()))).isTrue();
        makeDue(timer);
        runner.runDueJobs();

        assertThat(reload(timer).getStatus()).isEqualTo(Job.Status.CANCELLED);
        assertThat(TestBackgroundWork.JOB_RUNS).doesNotContainKey(timer.getId());
    }

    @Test
    void aJobWhoseWorkerDiedIsTakenOverWhenTheLeaseExpires() {
        Job job = schedule(TestBackgroundWork.OK, Map.of(), Duration.ofHours(1));
        // simulate a worker that picked the job and then crashed (or Render put the container to sleep)
        jdbc.sql("""
                        UPDATE job SET status = 'RUNNING', locked_by = 'dead-worker', attempts = 1,
                                       locked_until = :expired WHERE id = :id""")
                .param("expired", Timestamp.from(Instant.now().minusSeconds(60))).param("id", job.getId()).update();

        eventually().until(() -> reload(job).getStatus() == Job.Status.DONE);
        assertThat(reload(job).getAttempts()).isEqualTo(2);
    }

    @Test
    void aJobThatKilledItsWorkerOnTheLastAttemptEndsFailed() {
        Job job = schedule(TestBackgroundWork.OK, Map.of(), Duration.ofHours(1));
        jdbc.sql("""
                        UPDATE job SET status = 'RUNNING', locked_by = 'dead-worker', attempts = max_attempts,
                                       locked_until = :expired WHERE id = :id""")
                .param("expired", Timestamp.from(Instant.now().minusSeconds(60))).param("id", job.getId()).update();

        eventually().until(() -> reload(job).getStatus() == Job.Status.FAILED);
        assertThat(reload(job).getLastError()).contains("Lease expired on the final attempt");
    }

    @Test
    void twoWorkersPollingAtOnceNeverClaimTheSameJob() throws Exception {
        List<Long> scheduled = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            scheduled.add(schedule(TestBackgroundWork.OK, Map.of(), Duration.ofHours(1)).getId());
        }
        jdbc.sql("UPDATE job SET due_at = :now WHERE id IN (:ids)")
                .param("now", Timestamp.from(Instant.now().minusSeconds(1))).param("ids", scheduled).update();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Instant now = Instant.now();
            Callable<List<Long>> claim = () -> inTransaction(() ->
                    store.claimBatch("parallel-" + Thread.currentThread().getId(), now, now.plusSeconds(300), 20));
            List<Future<List<Long>>> results = pool.invokeAll(List.of(claim, claim));
            List<Long> a = results.get(0).get();
            List<Long> b = results.get(1).get();

            Set<Long> overlap = new HashSet<>(a);
            overlap.retainAll(b);
            assertThat(overlap).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }
}
