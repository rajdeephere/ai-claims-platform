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
    /** Assigned at intake or reassigned by a supervisor. */
    public static final String CLAIM_ASSIGNED = "CLAIM_ASSIGNED";
    /** Timers on an unanswered information request (reminder, escalation, expiry). */
    public static final String INFO_REQUEST_REMINDER = "INFO_REQUEST_REMINDER";
    public static final String INFO_REQUEST_OVERDUE = "INFO_REQUEST_OVERDUE";
    public static final String INFO_REQUEST_EXPIRED = "INFO_REQUEST_EXPIRED";
    /** An open claim's fraud score crossed the SIU threshold: someone should consider a referral. */
    public static final String HIGH_FRAUD_SCORE = "HIGH_FRAUD_SCORE";

    public static final String CLAIM_ID = "claimId";
    public static final String CLAIM_NUMBER = "claimNumber";
    public static final String CLAIMANT_USER_ID = "claimantUserId";
    public static final String FROM = "from";
    public static final String TO = "to";
    public static final String OUTCOME = "outcome";
    public static final String MESSAGE = "message";
    public static final String ASSIGNED_ADJUSTER_ID = "assignedAdjusterId";
    public static final String PREVIOUS_ADJUSTER_ID = "previousAdjusterId";
    public static final String SEGMENT = "segment";
    public static final String INFO_REQUEST_ID = "infoRequestId";
    public static final String FRAUD_SCORE = "fraudScore";
}
