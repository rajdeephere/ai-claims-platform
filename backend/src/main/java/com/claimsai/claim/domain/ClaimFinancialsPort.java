package com.claimsai.claim.domain;

import java.math.BigDecimal;

/**
 * What the claim needs to know about its money, defined here and implemented by the financials module
 * (dependency inversion, like RiskAssessmentPort): closing and withdrawing depend on it, but the claim
 * module never depends on financials.
 */
public interface ClaimFinancialsPort {

    /**
     * @param openExposures exposures not yet closed
     * @param pendingItems  approval requests pending, or payments approved but not yet issued
     */
    record Position(int openExposures, int pendingItems, boolean anyPaymentIssued, BigDecimal totalPaid) {
    }

    Position position(Long claimId);

    /** Closes every open exposure, releasing unused reserves (withdrawal, denial). Needs a transaction. */
    void releaseOpenExposures(Long claimId, Long actorId, String actorName, String reason);
}
