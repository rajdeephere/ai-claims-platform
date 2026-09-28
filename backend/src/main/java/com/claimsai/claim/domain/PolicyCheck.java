package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.ClaimFlag;
import com.claimsai.policy.domain.PolicySnapshot;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a reported loss against the policy. The result is VERIFIED or UNVERIFIED plus flags: an unverified
 * policy sends the claim to review (COMPLEX), it never denies it. Denial is a supervisor decision.
 */
public final class PolicyCheck {

    private PolicyCheck() {
    }

    public record Result(boolean verified, Set<ClaimFlag> flags) {
    }

    /**
     * @param claimantUserId the portal user who filed the claim, or null for a staff-taken FNOL
     */
    public static Result check(Optional<PolicySnapshot> found, LossType lossType, LocalDate lossDate,
                               Long claimantUserId) {
        if (found.isEmpty()) {
            return new Result(false, EnumSet.of(ClaimFlag.POLICY_NOT_FOUND));
        }
        PolicySnapshot policy = found.get();
        Set<ClaimFlag> flags = EnumSet.noneOf(ClaimFlag.class);
        if (!policy.inForceOn(lossDate)) {
            flags.add(ClaimFlag.POLICY_NOT_IN_FORCE);
        }
        if (policy.product() != lossType.product() || !policy.covers(lossType.coverage())) {
            flags.add(ClaimFlag.NOT_COVERED);
        }
        // a portal user claiming on someone else's policy: may be legitimate (a family member), must be checked
        if (claimantUserId != null && !claimantUserId.equals(policy.holderUserId())) {
            flags.add(ClaimFlag.HOLDER_MISMATCH);
        }
        return new Result(flags.isEmpty(), flags);
    }
}
