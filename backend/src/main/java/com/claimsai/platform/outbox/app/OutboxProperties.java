package com.claimsai.platform.outbox.app;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** {@code app.outbox.*} */
@Validated
@ConfigurationProperties("app.outbox")
public record OutboxProperties(
        boolean enabled,
        @Min(1) int batchSize,
        @Min(1) int maxAttempts,
        @NotNull Duration retryDelay) {
}
