package com.claimsai.financials.domain;

/** Outbox events of the financials module (the notification module listens to PAYMENT_ISSUED). */
public final class FinancialsEvents {

    private FinancialsEvents() {
    }

    public static final String AGGREGATE = "PAYMENT";
    public static final String PAYMENT_ISSUED = "PAYMENT_ISSUED";

    public static final String CLAIM_ID = "claimId";
    public static final String CLAIM_NUMBER = "claimNumber";
    public static final String CLAIMANT_USER_ID = "claimantUserId";
    public static final String AMOUNT = "amount";
    public static final String PAYEE = "payee";
}
