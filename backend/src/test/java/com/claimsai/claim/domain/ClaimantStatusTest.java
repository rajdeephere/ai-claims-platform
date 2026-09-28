package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimantStatusTest {

    @Test
    void aFraudInvestigationLooksLikeANormalReviewToTheClaimant() {
        assertThat(ClaimantStatus.of(ClaimStatus.SIU_REVIEW, null)).isEqualTo(ClaimantStatus.IN_REVIEW);
        assertThat(ClaimantStatus.of(ClaimStatus.OPEN, null)).isEqualTo(ClaimantStatus.IN_REVIEW);
    }

    @Test
    void mapping() {
        assertThat(ClaimantStatus.of(ClaimStatus.SUBMITTED, null)).isEqualTo(ClaimantStatus.RECEIVED);
        assertThat(ClaimantStatus.of(ClaimStatus.ASSESSING, null)).isEqualTo(ClaimantStatus.RECEIVED);
        assertThat(ClaimantStatus.of(ClaimStatus.AWAITING_INFO, null)).isEqualTo(ClaimantStatus.ACTION_NEEDED);
        assertThat(ClaimantStatus.of(ClaimStatus.CLOSED, CloseOutcome.PAID)).isEqualTo(ClaimantStatus.PAID);
        assertThat(ClaimantStatus.of(ClaimStatus.CLOSED, CloseOutcome.DENIED)).isEqualTo(ClaimantStatus.DENIED);
        assertThat(ClaimantStatus.of(ClaimStatus.CLOSED, CloseOutcome.WITHDRAWN)).isEqualTo(ClaimantStatus.WITHDRAWN);
        assertThat(ClaimantStatus.of(ClaimStatus.CLOSED, CloseOutcome.NO_PAYMENT))
                .isEqualTo(ClaimantStatus.CLOSED_NO_PAYMENT);
    }
}
