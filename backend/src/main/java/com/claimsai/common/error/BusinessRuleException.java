package com.claimsai.common.error;

import org.springframework.http.HttpStatus;

/** HTTP 422: well-formed, but breaks a business rule (over authority limit, SIU hold, self-approval). */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
