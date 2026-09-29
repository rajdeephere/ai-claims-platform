package com.claimsai.claim.domain;

/** Small value types of the claim, kept together. */
public final class ClaimEnums {

    private ClaimEnums() {
    }

    /** Why a claim is CLOSED. One CLOSED state with an outcome, because all outcomes behave the same. */
    public enum CloseOutcome { PAID, DENIED, WITHDRAWN, NO_PAYMENT }

    /** Handling track decided by triage. */
    public enum Segment { FAST_TRACK, STANDARD, COMPLEX }

    public enum PolicyVerification { PENDING, VERIFIED, UNVERIFIED }

    /** Things the adjuster must look at. Flags inform; they never deny a claim on their own. */
    public enum ClaimFlag {
        POLICY_NOT_FOUND,
        POLICY_NOT_IN_FORCE,
        NOT_COVERED,
        HOLDER_MISMATCH,
        UNASSIGNED,
        /** The assessment didn't finish in time; the claim moved on without it. */
        ASSESSMENT_TIMED_OUT
    }
}
