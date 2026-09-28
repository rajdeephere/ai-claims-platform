package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/** HTTP 412: If-Match does not match the current version. */
public class PreconditionFailedException extends ApiException {

    public PreconditionFailedException(String code, String message) {
        super(HttpStatus.PRECONDITION_FAILED, code, message);
    }
}
