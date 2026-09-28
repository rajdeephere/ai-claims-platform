package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;

/**
 * The status a claimant sees. Deliberately coarser than {@link ClaimStatus}: SIU_REVIEW shows as IN_REVIEW,
 * because telling someone they are under fraud investigation would tip them off.
 */
public enum ClaimantStatus {
    RECEIVED,
    IN_REVIEW,
    ACTION_NEEDED,
    PAID,
    DENIED,
    WITHDRAWN,
    CLOSED_NO_PAYMENT;

    public static ClaimantStatus of(ClaimStatus status, CloseOutcome outcome) {
        return switch (status) {
            case SUBMITTED, ASSESSING -> RECEIVED;
            case OPEN, SIU_REVIEW -> IN_REVIEW;
            case AWAITING_INFO -> ACTION_NEEDED;
            case CLOSED -> switch (outcome) {
                case PAID -> PAID;
                case DENIED -> DENIED;
                case WITHDRAWN -> WITHDRAWN;
                case NO_PAYMENT -> CLOSED_NO_PAYMENT;
            };
        };
    }
}
