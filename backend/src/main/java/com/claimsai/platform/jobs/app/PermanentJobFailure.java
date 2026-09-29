package com.claimsai.platform.jobs.app;

/**
 * Thrown by a handler when retrying can't help (bad payload, the target no longer exists): the job goes
 * straight to FAILED instead of burning its remaining attempts.
 */
public class PermanentJobFailure extends RuntimeException {

    public PermanentJobFailure(String message) {
        super(message);
    }
}
