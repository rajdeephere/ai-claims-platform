package com.claimsai.financials.domain;

import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.ConflictException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialsDomainTest {

    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    static final Long ADJUSTER = 3L;
    static final Long SUPERVISOR = 5L;

    static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    @Nested
    class Exposures {

        Exposure exposure(String reserve) {
            Exposure e = Exposure.open(1L, Exposure.Type.VEHICLE_DAMAGE, "COLLISION", "Asha", ADJUSTER, NOW);
            e.setReserve(amount(reserve));
            return e;
        }

        @Test
        void availableIsReserveMinusPaidMinusWhatIsAlreadyCommitted() {
            Exposure e = exposure("4000");
            e.recordPayment(amount("1000"));

            assertThat(e.available(amount("500"))).isEqualByComparingTo("2500");
            assertThat(e.getPaidAmount()).hasToString("1000.00");
        }

        @Test
        void theReserveCanNeverDropBelowWhatWasPaid() {
            Exposure e = exposure("4000");
            e.recordPayment(amount("3000"));

            assertThatThrownBy(() -> e.setReserve(amount("2999.99")))
                    .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("RESERVE_BELOW_PAID");
            assertThatThrownBy(() -> e.recordPayment(amount("1000.01"))).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void closingReleasesTheUnusedReserveAndFreezesTheExposure() {
            Exposure e = exposure("4000");
            e.recordPayment(amount("3800"));

            e.close(NOW);

            assertThat(e.getReserveAmount()).isEqualByComparingTo("3800");
            assertThat(e.isOpen()).isFalse();
            assertThatThrownBy(() -> e.setReserve(amount("5000"))).isInstanceOf(ConflictException.class);
        }

        @Test
        void amountsWithMoreThanTwoDecimalsAreRefused() {
            assertThatThrownBy(() -> exposure("100.005")).isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class Payments {

        @Test
        void withinAuthorityIsApprovedByTheRequesterThemselves() {
            Payment p = Payment.requested(1L, 2L, amount("3800"), "City Motors", "key", ADJUSTER, true, NOW);

            assertThat(p.getStatus()).isEqualTo(Payment.Status.APPROVED);
            assertThat(p.getApprovedBy()).isEqualTo(ADJUSTER);
            assertThat(p.isCommitted()).isTrue();
        }

        @Test
        void aboveAuthorityWaitsForApprovalThenIssues() {
            Payment p = Payment.requested(1L, 2L, amount("12000"), "City Motors", "key", ADJUSTER, false, NOW);
            assertThat(p.getStatus()).isEqualTo(Payment.Status.PENDING_APPROVAL);
            assertThatThrownBy(() -> p.issued("REF", NOW)).isInstanceOf(IllegalStateException.class);

            p.approvedBy(SUPERVISOR);
            p.issued("PAY-1", NOW);

            assertThat(p.getStatus()).isEqualTo(Payment.Status.ISSUED);
            assertThat(p.isCommitted()).isFalse();
        }

        @Test
        void aRejectedOrFailedPaymentNoLongerHoldsReserve() {
            Payment rejected = Payment.requested(1L, 2L, amount("12000"), "x", "k1", ADJUSTER, false, NOW);
            rejected.rejected();
            Payment failed = Payment.requested(1L, 2L, amount("100"), "x", "k2", ADJUSTER, true, NOW);
            failed.failed("account closed");

            assertThat(rejected.isCommitted()).isFalse();
            assertThat(failed.isCommitted()).isFalse();
        }
    }

    @Nested
    class Approvals {

        ApprovalRequest pending() {
            return ApprovalRequest.pending(1L, ApprovalRequest.Kind.PAYMENT, 9L, amount("12000"), "garage invoice",
                    ADJUSTER, NOW);
        }

        @Test
        void theRequesterCanNeverDecideTheirOwnRequest() {
            ApprovalRequest r = pending();

            assertThatThrownBy(() -> r.approve(ADJUSTER, null, NOW))
                    .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("SELF_APPROVAL");
            assertThatThrownBy(() -> r.reject(ADJUSTER, "no", NOW)).isInstanceOf(BusinessRuleException.class);
        }

        @Test
        void aRequestIsDecidedOnce() {
            ApprovalRequest r = pending();
            r.approve(SUPERVISOR, "ok", NOW);

            assertThat(r.getDecidedBy()).isEqualTo(SUPERVISOR);
            assertThatThrownBy(() -> r.reject(SUPERVISOR, "changed my mind", NOW))
                    .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("ALREADY_DECIDED");
        }

        @Test
        void aRejectionNeedsAReason() {
            assertThatThrownBy(() -> pending().reject(SUPERVISOR, " ", NOW))
                    .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("REASON_REQUIRED");
        }
    }
}
