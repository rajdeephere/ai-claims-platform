package com.claimsai.document.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * {@code app.storage.*}: any S3-compatible store.
 *
 * @param endpoint        where the API reaches the store
 * @param presignEndpoint where browsers reach it, if different (e.g. the API runs in Docker); defaults to endpoint
 * @param createBucket    create the bucket at startup if missing (local and tests only)
 */
@Validated
@ConfigurationProperties("app.storage")
public record StorageProperties(
        @NotNull URI endpoint,
        URI presignEndpoint,
        @NotBlank String region,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        boolean createBucket,
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration downloadUrlTtl) {

    /** An unset environment variable binds as an empty URI, not null: treat both as "same as endpoint". */
    public URI browserEndpoint() {
        return presignEndpoint != null && !presignEndpoint.toString().isBlank() ? presignEndpoint : endpoint;
    }
}
