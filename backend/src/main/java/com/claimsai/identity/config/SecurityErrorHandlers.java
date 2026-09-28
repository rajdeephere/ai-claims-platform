package com.claimsai.identity.config;

import com.claimsai.common.error.ApiError;
import com.claimsai.common.error.ApiErrors;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 401 and 403 raised by the security filters happen before any controller, so the MVC exception handler
 * never sees them. These write the same ApiError body, so clients handle one error format.
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        // RFC 6750: tell the client which scheme is expected
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, request, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Missing, invalid or expired access token");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission for this action");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String code,
                       String message) throws IOException {
        ApiError body = ApiErrors.of(status, code, message, request.getRequestURI(), List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
