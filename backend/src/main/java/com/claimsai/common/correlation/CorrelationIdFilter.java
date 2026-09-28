package com.claimsai.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Accepts X-Correlation-Id from the caller (or generates one), puts it in the logging MDC so every log line
 * of the request carries it, and echoes it back. Runs before the security filter chain, so 401/403 error
 * bodies carry it too. Later phases copy it onto jobs and outbox events.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Caller-supplied IDs end up in logs: accept only a safe, bounded format (no log injection). */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CorrelationId.HEADER);
        if (correlationId == null || !SAFE_ID.matcher(correlationId).matches()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat reuses threads: without this the next request on the thread would inherit the ID.
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
