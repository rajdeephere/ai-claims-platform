package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/** HTTP 404: the resource does not exist, or this user may not see it (existence is not revealed). */
public class NotFoundException extends ApiException {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
