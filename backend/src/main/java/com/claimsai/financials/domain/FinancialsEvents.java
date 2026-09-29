package com.claimsai.financials.domain;

/** Outbox events of the financials module (notifications listen to PAYMENT_ISSUED, activities to all). */
public final class FinancialsEvents {

    private FinancialsEvents() {
    }

    public static final String AGGREGATE = "PAYMENT";
    public static final String PAYMENT_ISSUED = "PAYMENT_ISSUED";
    /** The rail refused the payment: it is FAILED and its money available again. */
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";
    /** The rail stayed unreachable through every retry: nobody knows whether the money left. */
    public static final String PAYMENT_STUCK = "PAYMENT_STUCK";

    public static final String PAYMENT_ID = "paymentId";

    public static final String CLAIM_ID = "claimId";
    public static final String CLAIM_NUMBER = "claimNumber";
    public static final String CLAIMANT_USER_ID = "claimantUserId";
    /** Always a string with two decimals ("3800.00"), never a JSON number (BUG-012). */
    public static final String AMOUNT = "amount";
    public static final String PAYEE = "payee";
}
