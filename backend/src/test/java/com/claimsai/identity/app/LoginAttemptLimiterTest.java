package com.claimsai.identity.app;

import com.claimsai.common.error.RateLimitedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptLimiterTest {

    @Test
    void theSixthFailedAttemptWithinAMinuteIsRefusedForThatUserOnly() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 1000);
        for (int i = 0; i < 5; i++) {
            limiter.checkAllowed("Adjuster1", "10.0.0.1");
            limiter.recordFailure("Adjuster1", "10.0.0.1");
        }

        assertThatThrownBy(() -> limiter.checkAllowed("adjuster1", "10.0.0.2"))   // case-insensitive, any IP
                .isInstanceOf(RateLimitedException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((RateLimitedException) e).retryAfterSeconds())
                        .isBetween(1L, 60L));
        assertThatCode(() -> limiter.checkAllowed("adjuster2", "10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    void oneIpTryingManyUsernamesIsStoppedToo() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, 3);
        limiter.recordFailure("a", "10.9.9.9");
        limiter.recordFailure("b", "10.9.9.9");
        limiter.recordFailure("c", "10.9.9.9");

        assertThatThrownBy(() -> limiter.checkAllowed("d", "10.9.9.9")).isInstanceOf(RateLimitedException.class);
    }

    @Test
    void successfulLoginsAreNeverCounted() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(1, 1);

        for (int i = 0; i < 50; i++) {
            limiter.checkAllowed("busy-user", "10.1.1.1");   // checked, but no failure recorded
        }
    }
}
