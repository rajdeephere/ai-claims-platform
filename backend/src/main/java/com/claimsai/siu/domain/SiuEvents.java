package com.claimsai.siu.domain;

/**
 * Outbox events of the SIU module (the activity module listens). Never carries the claimant: a claimant
 * must not learn about an investigation, so no notification can be built from these.
 */
public final class SiuEvents {

    private SiuEvents() {
    }

    public static final String AGGREGATE = "SIU_CASE";
    public static final String SIU_CASE_OPENED = "SIU_CASE_OPENED";
    public static final String SIU_CASE_DECIDED = "SIU_CASE_DECIDED";

    public static final String CASE_ID = "caseId";
    public static final String CLAIM_ID = "claimId";
    public static final String CLAIM_NUMBER = "claimNumber";
    public static final String SOURCE = "source";
    public static final String OUTCOME = "outcome";
}
