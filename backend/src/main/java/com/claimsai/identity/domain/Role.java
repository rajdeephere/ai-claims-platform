package com.claimsai.identity.domain;

/** The four v1 roles. Authority limits are per user (in the database), not per role. */
public enum Role {
    /** Policyholder or third party: reports losses, uploads documents, sees only their own claims. */
    CLAIMANT,
    /** Owns claims: coverage, exposures, reserves, payments within their limit, SIU referral, recovery. */
    ADJUSTER,
    /** Checker: approves over-limit amounts and every denial, reassigns and reopens claims. */
    SUPERVISOR,
    /** Special Investigations Unit: investigates fraud referrals; can never pay. */
    SIU;

    /** Spring Security authority name, e.g. ROLE_ADJUSTER (what hasRole('ADJUSTER') checks). */
    public String authority() {
        return "ROLE_" + name();
    }

    public boolean isStaff() {
        return this != CLAIMANT;
    }
}
