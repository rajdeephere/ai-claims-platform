package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.ClaimFlag;
import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;
import com.claimsai.claim.domain.ClaimEnums.PolicyVerification;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.common.error.BusinessRuleException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaimTest {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    static Claim.LossReport report() {
        return new Claim.LossReport("POL-AUTO-1001", TODAY.minusDays(2), LossType.VEHICLE_COLLISION, "MG Road",
                "Rear-ended at a signal", false, new BigDecimal("3800"), "Asha Verma", null);
    }

    static Claim submitted() {
        return Claim.submit("CLM-2026-000001", report(), 1L, 1L, TODAY, NOW);
    }

    static Claim open() {
        Claim claim = submitted();
        claim.startAssessment(new PolicyCheck.Result(true, Set.of()), null, NOW);
        claim.completeAssessment(new TriageRules.Decision(Segment.STANDARD, false, "test"), NOW);
        claim.assignTo(3L, NOW);
        return claim;
    }

    @Test
    void fnolStartsSubmittedWithPendingPolicyAndMoneyAtTwoDecimals() {
        Claim claim = submitted();

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(claim.getPolicyVerification()).isEqualTo(PolicyVerification.PENDING);
        assertThat(claim.getEstimatedLoss()).isEqualByComparingTo("3800").hasToString("3800.00");
    }

    @Test
    void lossDateInTheFutureIsRefused() {
        Claim.LossReport future = new Claim.LossReport("POL-AUTO-1001", TODAY.plusDays(1), LossType.VEHICLE_GLASS,
                "x", "future loss", false, null, "A", null);

        assertThatThrownBy(() -> Claim.submit("CLM-1", future, 1L, 1L, TODAY, NOW))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("LOSS_DATE_IN_FUTURE");
    }

    @Test
    void unverifiedPolicyKeepsTheFlagsButStillGoesToAssessment() {
        Claim claim = submitted();

        claim.startAssessment(new PolicyCheck.Result(false, EnumSet.of(ClaimFlag.POLICY_NOT_IN_FORCE)), null, NOW);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.ASSESSING);
        assertThat(claim.getPolicyVerification()).isEqualTo(PolicyVerification.UNVERIFIED);
        assertThat(claim.getFlags()).containsExactly(ClaimFlag.POLICY_NOT_IN_FORCE);
    }

    @Test
    void triageCanReferStraightToSiu() {
        Claim claim = submitted();
        claim.startAssessment(new PolicyCheck.Result(true, Set.of()), null, NOW);

        claim.completeAssessment(new TriageRules.Decision(Segment.STANDARD, true, "fraud"), NOW);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SIU_REVIEW);
    }

    @Test
    void informationRequestRoundTrip() {
        Claim claim = open();

        assertThat(claim.requestInformation(NOW).to()).isEqualTo(ClaimStatus.AWAITING_INFO);
        assertThat(claim.informationReceived(NOW).to()).isEqualTo(ClaimStatus.OPEN);
    }

    @Test
    void anUnansweredRequestIsCancelledByTheAdjusterOrExpires() {
        Claim claim = open();
        claim.requestInformation(NOW);
        assertThat(claim.informationRequestCancelled(NOW).to()).isEqualTo(ClaimStatus.OPEN);

        claim.requestInformation(NOW);
        assertThat(claim.informationRequestExpired(NOW).from()).isEqualTo(ClaimStatus.AWAITING_INFO);
        assertThatThrownBy(() -> claim.informationRequestExpired(NOW)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void referringToSiuActsOnTheHighFraudScoreFlag() {
        Claim claim = open();
        claim.flagHighFraudScore(NOW);

        claim.referToSiu(NOW);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SIU_REVIEW);
        assertThat(claim.getFlags()).doesNotContain(ClaimFlag.HIGH_FRAUD_SCORE);
    }

    @Test
    void anInformationRequestIsDecidedOnce() {
        InfoRequest request = new InfoRequest(1L, "Please send the repair estimate", 3L, NOW);
        request.expire(NOW);

        assertThat(request.getStatus()).isEqualTo(InfoRequest.Status.EXPIRED);
        assertThatThrownBy(() -> request.answer("here it is", 1L, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeSetsTheOutcomeAndReopenClearsIt() {
        Claim claim = open();

        claim.close(false, NOW);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.CLOSED);
        assertThat(claim.getCloseOutcome()).isEqualTo(CloseOutcome.NO_PAYMENT);
        assertThat(claim.getClosedAt()).isEqualTo(NOW);

        claim.reopen(NOW.plusSeconds(60));
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.OPEN);
        assertThat(claim.getCloseOutcome()).isNull();
        assertThat(claim.getClosedAt()).isNull();
    }

    @Test
    void closeWithPaymentsIsPaid() {
        Claim claim = open();

        claim.close(true, NOW);

        assertThat(claim.getCloseOutcome()).isEqualTo(CloseOutcome.PAID);
    }

    @Nested
    class Guards {

        @Test
        void aClaimWaitingForTheClaimantCantBeClosedOrDenied() {
            Claim claim = open();
            claim.requestInformation(NOW);

            // AWAITING_INFO -> CLOSED exists in the table (for withdraw), but close/deny require OPEN
            assertThatThrownBy(() -> claim.close(false, NOW)).isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(() -> claim.deny(NOW)).isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void withdrawIsAllowedWhileOpenOrWaitingButNotOnceMoneyWentOut() {
            Claim waiting = open();
            waiting.requestInformation(NOW);
            assertThat(waiting.withdraw(false, NOW).to()).isEqualTo(ClaimStatus.CLOSED);
            assertThat(waiting.getCloseOutcome()).isEqualTo(CloseOutcome.WITHDRAWN);

            assertThatThrownBy(() -> open().withdraw(true, NOW))
                    .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("PAYMENT_ALREADY_ISSUED");
        }

        @Test
        void aNewClaimCantBeWithdrawnOrClosedBeforeIntakeFinishes() {
            assertThatThrownBy(() -> submitted().withdraw(false, NOW)).isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(() -> submitted().close(false, NOW)).isInstanceOf(InvalidTransitionException.class);
        }

        @Test
        void aClosedClaimCantBeReassignedOrAskedForInformation() {
            Claim claim = open();
            claim.close(false, NOW);

            assertThatThrownBy(() -> claim.assignTo(4L, NOW)).isInstanceOf(InvalidTransitionException.class);
            assertThatThrownBy(() -> claim.requestInformation(NOW))
                    .isInstanceOf(InvalidTransitionException.class)
                    .hasMessage("Cannot request information on a claim in status CLOSED");
        }

        @Test
        void siuReviewOnlyEndsBackInOpen() {
            Claim claim = open();
            claim.referToSiu(NOW);

            assertThatThrownBy(() -> claim.close(false, NOW)).isInstanceOf(InvalidTransitionException.class);
            assertThat(claim.siuReviewCompleted(NOW).to()).isEqualTo(ClaimStatus.OPEN);
        }
    }

    @Test
    void assigningClearsTheUnassignedFlagAndReturnsThePreviousAdjuster() {
        Claim claim = submitted();
        claim.startAssessment(new PolicyCheck.Result(true, Set.of()), null, NOW);
        claim.completeAssessment(new TriageRules.Decision(Segment.STANDARD, false, "x"), NOW);
        claim.markUnassigned(NOW);
        assertThat(claim.getFlags()).contains(ClaimFlag.UNASSIGNED);

        assertThat(claim.assignTo(3L, NOW)).isNull();
        assertThat(claim.assignTo(4L, NOW)).isEqualTo(3L);
        assertThat(claim.getFlags()).doesNotContain(ClaimFlag.UNASSIGNED);
    }
}
