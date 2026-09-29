package com.claimsai.claim.domain;

/**
 * Event types the claim module publishes through the outbox, and their payload keys. Payloads are
 * self-contained (event-carried state): a listener never has to query the claim module back.
 */
public final class ClaimEvents {

    private ClaimEvents() {
    }

    public static final String AGGREGATE = "CLAIM";

    public static final String CLAIM_SUBMITTED = "CLAIM_SUBMITTED";
    public static final String CLAIM_STATUS_CHANGED = "CLAIM_STATUS_CHANGED";
    public static final String INFO_REQUESTED = "INFO_REQUESTED";

    public static final String CLAIM_ID = "claimId";
    public static final String CLAIM_NUMBER = "claimNumber";
    public static final String CLAIMANT_USER_ID = "claimantUserId";
    public static final String FROM = "from";
    public static final String TO = "to";
    public static final String OUTCOME = "outcome";
    public static final String MESSAGE = "message";
}
