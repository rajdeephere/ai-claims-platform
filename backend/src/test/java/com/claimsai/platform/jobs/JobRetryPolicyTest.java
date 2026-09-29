package com.claimsai.platform.jobs;

import com.claimsai.platform.jobs.app.JobProperties;
import com.claimsai.platform.jobs.domain.Job;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobRetryPolicyTest {

    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    static final JobProperties PROPS = new JobProperties(true, 5, Duration.ofMinutes(5), 5,
            List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1)));

    static Job running(int attempts, int maxAttempts) {
        Job job = instantiate();
        ReflectionTestUtils.setField(job, "status", Job.Status.RUNNING);
        ReflectionTestUtils.setField(job, "attempts", attempts);
        ReflectionTestUtils.setField(job, "maxAttempts", maxAttempts);
        ReflectionTestUtils.setField(job, "lockedBy", "worker-1");
        ReflectionTestUtils.setField(job, "payload", Map.of());
        return job;
    }

    private static Job instantiate() {
        try {
            var constructor = Job.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void backoffGrowsAndThenStaysAtTheLastStep() {
        assertThat(PROPS.backoffAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(PROPS.backoffAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(PROPS.backoffAfter(4)).isEqualTo(Duration.ofHours(1));
        assertThat(PROPS.backoffAfter(9)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void aFailureWithAttemptsLeftGoesBackToPendingAndReleasesTheLease() {
        Job job = running(2, 5);

        job.recordFailure("boom", false, NOW.plusSeconds(120), NOW);

        assertThat(job.getStatus()).isEqualTo(Job.Status.PENDING);
        assertThat(job.getDueAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(job.getLastError()).isEqualTo("boom");
        assertThat(job.isLeasedBy("worker-1")).isFalse();
    }

    @Test
    void theLastAttemptFailingMakesTheJobDead() {
        Job job = running(5, 5);

        job.recordFailure("still broken", false, NOW.plusSeconds(3600), NOW);

        assertThat(job.getStatus()).isEqualTo(Job.Status.FAILED);
        assertThat(job.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void aPermanentFailureDoesNotBurnTheRemainingAttempts() {
        Job job = running(1, 5);

        job.recordFailure("claim does not exist", true, NOW.plusSeconds(30), NOW);

        assertThat(job.getStatus()).isEqualTo(Job.Status.FAILED);
    }

    @Test
    void onlyAFailedJobCanBeRetriedAndItGetsFreshAttempts() {
        Job job = running(5, 5);
        assertThatThrownBy(() -> job.retryNow(NOW)).isInstanceOf(IllegalStateException.class);

        job.recordFailure("x", false, NOW, NOW);
        job.retryNow(NOW.plusSeconds(10));

        assertThat(job.getStatus()).isEqualTo(Job.Status.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getDueAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void veryLongErrorsAreTruncatedToTheColumn() {
        Job job = running(1, 5);

        job.recordFailure("x".repeat(5000), false, NOW, NOW);

        assertThat(job.getLastError()).hasSize(2000);
    }
}
