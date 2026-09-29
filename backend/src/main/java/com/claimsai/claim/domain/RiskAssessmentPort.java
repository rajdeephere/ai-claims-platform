package com.claimsai.claim.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What claim intake needs from the risk assessment, defined here and implemented by the ai module
 * (dependency inversion): the claim module never depends on the ai module, so there is no cycle
 * claim -> ai -> document -> claim.
 */
public interface RiskAssessmentPort {

    /**
     * @param policyStart         the policy's start date as seen at the policy check, or null if unknown
     * @param otherClaimsOnPolicy claims on the same policy in the 12 months before this loss, this one excluded
     */
    record ClaimRiskFacts(Long claimId, String policyNumber, LocalDate lossDate, LocalDate policyStart,
                          BigDecimal estimatedLoss, long otherClaimsOnPolicy) {
    }

    /**
     * @param fraudScore     0-100
     * @param reasons        one line per contributing signal, for the audit trail and the adjuster
     * @param assessedAmount the highest amount found in documents (estimate, invoice, damage), or null
     */
    record RiskAssessment(int fraudScore, List<String> reasons, BigDecimal assessedAmount) {
    }

    /** Are documents of this claim still being assessed? Triage waits for them (bounded by the timeout). */
    boolean assessmentPending(Long claimId);

    /** Scores the claim from its facts and every document assessment so far. Needs a transaction. */
    RiskAssessment assess(ClaimRiskFacts facts);
}
