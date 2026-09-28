package com.claimsai.common.error;

import java.time.Instant;
import java.util.List;

/**
 * The single error body of the API. {@code code} is stable and machine-readable (the UI switches on it);
 * {@code message} is for people and may change.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
