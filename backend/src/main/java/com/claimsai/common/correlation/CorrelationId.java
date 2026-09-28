package com.claimsai.common.correlation;

import org.slf4j.MDC;

/** Name of the correlation header and MDC key, plus access to the current request's value. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {
    }

    /** The current request's correlation ID, or null outside a request. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
