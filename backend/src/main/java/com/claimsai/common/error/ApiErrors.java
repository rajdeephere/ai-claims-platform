package com.claimsai.common.error;

import com.claimsai.common.correlation.CorrelationId;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.time.Instant;
import java.util.List;

/** Builds {@link ApiError} bodies the same way for the MVC exception handler and the security filters. */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static ApiError of(HttpStatusCode status, String code, String message, String path,
                              List<ApiError.FieldViolation> violations) {
        String reason = status instanceof HttpStatus hs ? hs.getReasonPhrase() : String.valueOf(status.value());
        return new ApiError(Instant.now(), status.value(), reason, code, message, path,
                CorrelationId.current(), violations);
    }

    /** Code for a status without a more specific one: NOT_FOUND, METHOD_NOT_ALLOWED, ... */
    public static String defaultCode(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.name() : "HTTP_" + status.value();
    }
}
