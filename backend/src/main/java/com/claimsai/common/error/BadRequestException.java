package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/** HTTP 400 for request problems bean validation can't express (e.g. a required header with a specific code). */
public class BadRequestException extends ApiException {

    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
