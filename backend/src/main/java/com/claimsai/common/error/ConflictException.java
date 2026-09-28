package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/** HTTP 409: conflicts with the current state (invalid transition, duplicate, concurrent change). */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
