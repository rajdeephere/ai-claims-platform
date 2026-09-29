package com.claimsai.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Exception to HTTP mapping for the whole API. Extends {@link ResponseEntityExceptionHandler}, which already
 * picks the right status for every standard Spring MVC exception (400, 404 unknown route, 405, 415 ...);
 * we only render our {@link ApiError} body. Lessons carried over from ClaimFlow: a catch-all handler alone
 * turns unknown routes into 500s, and Spring 6.1 method validation needs its own override.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest req) {
        ResponseEntity<ApiError> response = build(ex.status(), ex.code(), ex.getMessage(), req.getRequestURI(), List.of());
        if (ex instanceof RateLimitedException limited) {
            return ResponseEntity.status(response.getStatusCode())
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()))
                    .body(response.getBody());
        }
        return response;
    }

    /** JPA @Version: another transaction changed the row first; the loser gets 409 instead of overwriting. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
        log.info("Concurrent update rejected on {} {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.CONFLICT, "CONCURRENT_UPDATE",
                "The resource was changed by another request. Reload and retry.", req.getRequestURI(), List.of());
    }

    /**
     * Method security (@PreAuthorize) throws inside the controller call, so the exception reaches this advice,
     * not the security filter's access-denied handler. Without this handler it would become a 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required",
                req.getRequestURI(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        // log the cause (with the correlation ID from the MDC); never leak internals to the client
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error",
                req.getRequestURI(), List.of());
    }

    // ---- standard Spring MVC exceptions: Spring chose the status, we render the body ----

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return toObject(build(status, "VALIDATION_FAILED", "Request validation failed", path(request), violations));
    }

    /** Spring 6.1+: with constraints on handler parameters, @Valid bodies are reported through this exception. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<ApiError.FieldViolation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                errors.getFieldErrors().forEach(fe ->
                        violations.add(new ApiError.FieldViolation(fe.getField(), fe.getDefaultMessage())));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(err ->
                        violations.add(new ApiError.FieldViolation(name, err.getDefaultMessage())));
            }
        }
        return toObject(build(status, "VALIDATION_FAILED", "Request validation failed", path(request), violations));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String message = body instanceof ProblemDetail pd && pd.getDetail() != null ? pd.getDetail() : ex.getMessage();
        return toObject(build(status, ApiErrors.defaultCode(status), message, path(request), List.of()));
    }

    private static ResponseEntity<ApiError> build(HttpStatusCode status, String code, String message, String path,
                                                  List<ApiError.FieldViolation> violations) {
        return ResponseEntity.status(status).body(ApiErrors.of(status, code, message, path, violations));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> toObject(ResponseEntity<ApiError> response) {
        return (ResponseEntity) response;
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }
}
