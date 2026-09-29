package com.claimsai.ai.domain;

/**
 * Port to a large language model. Adapters: Groq (OpenAI-compatible HTTP API) and a deterministic stub.
 * Switching provider (OpenAI, Gemini, a local model) means another adapter, nothing else.
 */
public interface LlmClient {

    /**
     * @param system     role and output rules
     * @param userText   the task plus the document text, clearly delimited as data
     * @param imageJpeg  a page or photo for a vision model, or null
     * @param jsonOutput ask the model for a single JSON object
     */
    record LlmRequest(String system, String userText, byte[] imageJpeg, boolean jsonOutput, int maxTokens) {
    }

    record LlmResponse(String content, String model, Integer tokensIn, Integer tokensOut) {
    }

    /**
     * @throws LlmUnavailableException the call failed in a way a retry may fix (timeout, 5xx, rate limit)
     * @throws LlmRejectedException    the provider refused the request itself; retrying won't help
     */
    LlmResponse complete(LlmRequest request);

    /** Name for logs and stored assessments, e.g. "groq". */
    String provider();

    /** Temporary failure: retry later. */
    class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The provider (or our own limiter) says "not now". */
    class LlmRateLimitedException extends LlmUnavailableException {
        public LlmRateLimitedException(String message) {
            super(message, null);
        }
    }

    /** Permanent: bad request, unknown model, content refused. */
    class LlmRejectedException extends RuntimeException {
        public LlmRejectedException(String message) {
            super(message);
        }
    }
}
