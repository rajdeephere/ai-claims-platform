package com.claimsai.identity.app;

import com.claimsai.common.error.RateLimitedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Brute-force protection for login. Only FAILED attempts are counted, per username and per client IP, so a
 * user who logs in correctly is never slowed down. Over the limit, even the right password is refused until
 * the window refills: that's what makes guessing pointless.
 *
 * <p>In memory (Caffeine, idle entries expire): enough for one instance; with several, a shared store
 * (Redis, bucket4j-postgresql) would replace the cache.
 */
@Component
public class LoginAttemptLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(15, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();
    private final int perUserPerMinute;
    private final int perIpPerMinute;

    public LoginAttemptLimiter(@Value("${app.security.login-limit.failures-per-user-per-minute:5}") int perUserPerMinute,
                               @Value("${app.security.login-limit.failures-per-ip-per-minute:30}") int perIpPerMinute) {
        this.perUserPerMinute = perUserPerMinute;
        this.perIpPerMinute = perIpPerMinute;
    }

    /** Call before checking the password. */
    public void checkAllowed(String username, String ip) {
        long waitUser = waitSeconds(userBucket(username));
        long waitIp = waitSeconds(ipBucket(ip));
        long wait = Math.max(waitUser, waitIp);
        if (wait > 0) {
            throw new RateLimitedException("TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed login attempts. Try again in " + wait + " seconds.", wait);
        }
    }

    public void recordFailure(String username, String ip) {
        userBucket(username).tryConsume(1);
        ipBucket(ip).tryConsume(1);
    }

    private Bucket userBucket(String username) {
        return buckets.get("user:" + (username == null ? "" : username.toLowerCase(Locale.ROOT)),
                k -> bucket(perUserPerMinute));
    }

    private Bucket ipBucket(String ip) {
        return buckets.get("ip:" + ip, k -> bucket(perIpPerMinute));
    }

    private static Bucket bucket(int perMinute) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build();
    }

    /** 0 if a token is available, otherwise seconds until the next one. */
    private static long waitSeconds(Bucket bucket) {
        if (bucket.getAvailableTokens() > 0) {
            return 0;
        }
        long nanos = bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill();
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos) + 1);
    }
}
