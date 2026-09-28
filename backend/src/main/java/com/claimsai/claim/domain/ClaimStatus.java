package com.claimsai.claim.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The claim lifecycle (design section 6). The allowed moves are data, in one place: any move not listed
 * here is refused with 409, whatever code path asks for it.
 *
 * <pre>
 * SUBMITTED -> ASSESSING -> OPEN <-> AWAITING_INFO
 *                  |         ^ \          |
 *                  v         |  \         v
 *               SIU_REVIEW --+   +----> CLOSED -> OPEN (reopen)
 * </pre>
 */
public enum ClaimStatus {
    SUBMITTED,
    ASSESSING,
    OPEN,
    AWAITING_INFO,
    SIU_REVIEW,
    CLOSED;

    private static final Map<ClaimStatus, Set<ClaimStatus>> ALLOWED = new EnumMap<>(ClaimStatus.class);

    static {
        ALLOWED.put(SUBMITTED, EnumSet.of(ASSESSING));
        ALLOWED.put(ASSESSING, EnumSet.of(OPEN, SIU_REVIEW));
        ALLOWED.put(OPEN, EnumSet.of(AWAITING_INFO, SIU_REVIEW, CLOSED));
        ALLOWED.put(AWAITING_INFO, EnumSet.of(OPEN, CLOSED));
        ALLOWED.put(SIU_REVIEW, EnumSet.of(OPEN));
        ALLOWED.put(CLOSED, EnumSet.of(OPEN));
    }

    public boolean canMoveTo(ClaimStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<ClaimStatus> nextStates() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    /** Counts towards an adjuster's workload and can still be worked on. */
    public boolean isActive() {
        return this != CLOSED;
    }
}
