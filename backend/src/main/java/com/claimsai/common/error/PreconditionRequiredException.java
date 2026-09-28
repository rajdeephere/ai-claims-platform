package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/**
 * HTTP 428 (RFC 6585): a state-changing request came without If-Match. Requiring it means a client can't
 * overwrite a change it never saw just by leaving the header out.
 */
public class PreconditionRequiredException extends ApiException {

    public PreconditionRequiredException(String code, String message) {
        super(HttpStatus.PRECONDITION_REQUIRED, code, message);
    }
}
