package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/** HTTP 401: credentials or refresh token rejected. */
public class AuthenticationFailedException extends ApiException {

    public AuthenticationFailedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }
}
