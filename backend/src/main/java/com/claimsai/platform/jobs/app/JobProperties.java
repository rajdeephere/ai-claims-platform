package com.claimsai.platform.jobs.app;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * {@code app.jobs.*}
 *
 * @param lease   how long a picked-up job belongs to its worker; after that another worker may take it
 * @param backoff wait before retry N (the last value repeats)
 */
@Validated
@ConfigurationProperties("app.jobs")
public record JobProperties(
        boolean enabled,
        @Min(1) int batchSize,
        @NotNull Duration lease,
        @Min(1) int maxAttempts,
        @NotEmpty List<Duration> backoff) {

    /** Delay before the next try, after {@code attempt} failed attempts (1-based). */
    public Duration backoffAfter(int attempt) {
        return backoff.get(Math.min(Math.max(attempt, 1), backoff.size()) - 1);
    }
}
