package com.claimsai.claim.domain;

/**
 * What the claim module needs from SIU (Special Investigations Unit). Defined here, implemented by the siu
 * module, so the claim module never depends on it (the same inversion as {@link RiskAssessmentPort}).
 */
public interface SiuReferralPort {

    enum Source { RULE, MANUAL }

    /**
     * Opens an investigation, in the caller's transaction (the claim moves to SIU_REVIEW in it).
     *
     * @param referredBy     null when the triage rule referred the claim
     * @param referredByName the referrer's username, for the audit trail (null with referredBy)
     */
    void openCase(Long claimId, Source source, String reason, Long referredBy, String referredByName, Integer fraudScore);

    /** SIU investigators see a claim once it has (or had) a case. */
    boolean hasCase(Long claimId);
}
