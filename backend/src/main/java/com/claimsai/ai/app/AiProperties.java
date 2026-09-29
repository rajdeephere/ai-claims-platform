package com.claimsai.ai.app;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * {@code app.ai.*}
 *
 * @param provider          "stub" (default: tests, offline) or "groq"
 * @param maxDocumentChars  document text sent to the model is cut to this (token budget)
 * @param requestsPerMinute our own limit on outbound calls, kept below the provider's free tier
 */
@Validated
@ConfigurationProperties("app.ai")
public record AiProperties(
        @NotBlank String provider,
        @NotBlank String promptVersion,
        @Min(1000) int maxDocumentChars,
        @Min(1) int requestsPerMinute,
        @Valid @NotNull Groq groq) {

    public record Groq(@NotNull URI baseUrl, String apiKey, @NotBlank String textModel, @NotBlank String visionModel,
                       @NotNull Duration timeout) {
    }
}
