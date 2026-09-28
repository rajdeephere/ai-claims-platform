package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.Segment;

import java.math.BigDecimal;

/**
 * Decides the handling track of a new claim (the replacement for the DMN table in the Camunda design).
 * Evaluated in order; thresholds come from configuration ({@code app.triage.*}).
 *
 * <ol>
 *   <li>fraud score at or above the SIU threshold: refer to SIU (the segment is still decided)</li>
 *   <li>COMPLEX: injuries, policy not verified, or estimate above the complex threshold</li>
 *   <li>FAST_TRACK: small estimate, low fraud score, verified policy, no injuries. Needs a fraud score:
 *       a claim nobody has assessed is never fast-tracked</li>
 *   <li>otherwise STANDARD</li>
 * </ol>
 */
public final class TriageRules {

    private TriageRules() {
    }

    public record Thresholds(BigDecimal fastTrackMaxEstimate, int fastTrackMaxFraudScore,
                             BigDecimal complexMinEstimate, int siuReferralScore) {
    }

    /**
     * @param estimatedLoss claimant's (later the AI's) estimate, may be null
     * @param fraudScore    0-100, null until the AI assessment exists (phase 5)
     */
    public record Input(BigDecimal estimatedLoss, boolean injuries, boolean policyVerified, Integer fraudScore) {
    }

    public record Decision(Segment segment, boolean referToSiu, String reason) {
    }

    public static Decision decide(Input in, Thresholds t) {
        boolean referToSiu = in.fraudScore() != null && in.fraudScore() >= t.siuReferralScore();

        if (in.injuries()) {
            return new Decision(Segment.COMPLEX, referToSiu, "injuries reported");
        }
        if (!in.policyVerified()) {
            return new Decision(Segment.COMPLEX, referToSiu, "policy not verified");
        }
        if (in.estimatedLoss() != null && in.estimatedLoss().compareTo(t.complexMinEstimate()) > 0) {
            return new Decision(Segment.COMPLEX, referToSiu, "estimate above " + t.complexMinEstimate());
        }
        if (in.estimatedLoss() != null && in.estimatedLoss().compareTo(t.fastTrackMaxEstimate()) < 0
                && in.fraudScore() != null && in.fraudScore() < t.fastTrackMaxFraudScore()) {
            return new Decision(Segment.FAST_TRACK, referToSiu, "small, low-risk loss on a verified policy");
        }
        return new Decision(Segment.STANDARD, referToSiu, in.fraudScore() == null
                ? "no risk assessment yet" : "no fast-track or complex criteria met");
    }
}
