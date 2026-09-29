package com.claimsai.document.domain;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Port to object storage. The S3 adapter serves SeaweedFS locally and Supabase Storage in the cloud; any
 * S3-compatible store works by configuration.
 */
public interface DocumentStorage {

    /**
     * A URL the browser PUTs the file to directly, so the bytes never pass through the API. The type and
     * the exact size are part of the signature: a different file size is refused by the store itself.
     *
     * @param headers headers the client must send with the PUT
     */
    record PresignedUpload(URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record StoredObject(long sizeBytes) {
    }

    PresignedUpload presignUpload(String key, String contentType, long sizeBytes, Duration ttl);

    /** A short-lived download link that makes the browser save the file, never render it inline. */
    URI presignDownload(String key, String fileName, String contentType, Duration ttl);

    Optional<StoredObject> stat(String key);

    InputStream open(String key);

    /** Idempotent: deleting a missing object is not an error. */
    void delete(String key);
}
