package com.claimsai.claim.domain;

import com.claimsai.common.error.ConflictException;

/** 409: the requested action isn't possible in the claim's current status. */
public class InvalidTransitionException extends ConflictException {

    public InvalidTransitionException(String action, ClaimStatus current) {
        super("INVALID_TRANSITION", "Cannot " + action + " a claim in status " + current);
    }
}
