package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base of every exception the API deliberately returns to the client: it carries its HTTP status and a
 * stable error code, so one handler renders them all and new exceptions need no new handler.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
