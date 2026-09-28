package com.claimsai.policy.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * What the policy administration system tells us about a policy. Immutable: the claim keeps its own copy of
 * what matters (verification result, flags) and never holds a live reference to policy data.
 *
 * @param holderUserId the portal user who holds the policy, if linked
 * @param coverages    limit per coverage type
 */
public record PolicySnapshot(
        String policyNumber,
        Product product,
        Long holderUserId,
        String holderName,
        PolicyStatus status,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        BigDecimal deductible,
        Map<CoverageType, BigDecimal> coverages) {

    public enum Product { AUTO, HOME }

    public enum PolicyStatus { ACTIVE, CANCELLED, LAPSED }

    /**
     * Was the policy in force on the loss date? Judged by the period, not today's status: a policy that has
     * lapsed since still covers a loss that happened while it ran. A cancelled policy is never treated as
     * in force, because we don't know its cancellation date (the adjuster checks it).
     */
    public boolean inForceOn(LocalDate lossDate) {
        return status != PolicyStatus.CANCELLED
                && !lossDate.isBefore(effectiveFrom)
                && lossDate.isBefore(effectiveTo);
    }

    public boolean covers(CoverageType coverage) {
        return coverages.containsKey(coverage);
    }
}
