package com.claimsai.ai.app;

import com.claimsai.ai.domain.LlmClient;
import com.claimsai.ai.domain.LlmClient.LlmRateLimitedException;
import com.claimsai.ai.domain.LlmClient.LlmRequest;
import com.claimsai.ai.domain.LlmClient.LlmResponse;
import io.github.bucket4j.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Every LLM call goes through here: our own rate limit (below the free tier's, so we get "not now" from
 * ourselves, cheaply, instead of 429s from the provider), timing, and logging without content.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final LlmClient client;
    private final Bucket bucket;

    public LlmGateway(LlmClient client, AiProperties properties) {
        this.client = client;
        int perMinute = properties.requestsPerMinute();
        this.bucket = Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                .build();
    }

    public record TimedResponse(LlmResponse response, int latencyMs) {
    }

    /** @throws LlmRateLimitedException when our budget is spent: the job retries later */
    public TimedResponse call(LlmRequest request, String purpose) {
        if (!bucket.tryConsume(1)) {
            throw new LlmRateLimitedException("local LLM budget exhausted");
        }
        long start = System.nanoTime();
        LlmResponse response = client.complete(request);
        int latency = (int) ((System.nanoTime() - start) / 1_000_000);
        // ids and numbers only: document content is personal data
        log.info("LLM {} via {} model={} tokensIn={} tokensOut={} latencyMs={}", purpose, client.provider(),
                response.model(), response.tokensIn(), response.tokensOut(), latency);
        return new TimedResponse(response, latency);
    }

    public String provider() {
        return client.provider();
    }
}
