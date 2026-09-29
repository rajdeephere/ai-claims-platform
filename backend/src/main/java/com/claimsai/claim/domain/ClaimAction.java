package com.claimsai.claim.domain;

import com.claimsai.identity.domain.Role;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a user may do with a claim. Two separate questions, answered separately:
 * <ul>
 *   <li>{@link #permits}: may this role (and this relationship to the claim) ever do it? No: 403.</li>
 *   <li>{@link #availableIn}: does the claim's status allow it now? No: 409 INVALID_TRANSITION.</li>
 * </ul>
 * The API returns {@link #allowed} as {@code allowedActions}, so the UI shows exactly the buttons the
 * server would accept and never re-implements these rules.
 */
public enum ClaimAction {
    REQUEST_INFO(Set.of(ClaimStatus.OPEN)),
    CLOSE(Set.of(ClaimStatus.OPEN)),
    ADD_NOTE(EnumSet.allOf(ClaimStatus.class)),
    REASSIGN(EnumSet.complementOf(EnumSet.of(ClaimStatus.CLOSED))),
    REOPEN(Set.of(ClaimStatus.CLOSED)),
    RESPOND(Set.of(ClaimStatus.AWAITING_INFO)),
    WITHDRAW(Set.of(ClaimStatus.OPEN, ClaimStatus.AWAITING_INFO)),
    /** From the moment of FNOL until the claim is closed. */
    UPLOAD_DOCUMENT(EnumSet.complementOf(EnumSet.of(ClaimStatus.CLOSED))),
    /** Accept or correct what the AI read from a document. */
    REVIEW_AI(EnumSet.complementOf(EnumSet.of(ClaimStatus.CLOSED)));

    private final Set<ClaimStatus> statuses;

    ClaimAction(Set<ClaimStatus> statuses) {
        this.statuses = statuses;
    }

    public boolean availableIn(ClaimStatus status) {
        return statuses.contains(status);
    }

    /**
     * @param assignee true if the user is the claim's assigned adjuster
     * @param owner    true if the user is the claimant who filed it
     */
    public boolean permits(Role role, boolean assignee, boolean owner) {
        return switch (this) {
            case REQUEST_INFO, CLOSE -> role == Role.ADJUSTER && assignee;
            case ADD_NOTE -> role.isStaff();
            case REASSIGN, REOPEN -> role == Role.SUPERVISOR;
            case RESPOND, WITHDRAW -> role == Role.CLAIMANT && owner;
            case UPLOAD_DOCUMENT -> (role == Role.CLAIMANT && owner) || (role == Role.ADJUSTER && assignee)
                    || role == Role.SUPERVISOR;
            case REVIEW_AI -> (role == Role.ADJUSTER && assignee) || role == Role.SUPERVISOR;
        };
    }

    public static Set<ClaimAction> allowed(Claim claim, Long userId, Role role) {
        Set<ClaimAction> result = EnumSet.noneOf(ClaimAction.class);
        boolean assignee = claim.isAssignedTo(userId);
        boolean owner = claim.isFiledBy(userId);
        for (ClaimAction action : values()) {
            if (action.permits(role, assignee, owner) && action.availableIn(claim.getStatus())) {
                result.add(action);
            }
        }
        return result;
    }
}
