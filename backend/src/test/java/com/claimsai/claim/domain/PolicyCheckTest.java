package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.ClaimFlag;
import com.claimsai.policy.domain.CoverageType;
import com.claimsai.policy.domain.PolicySnapshot;
import com.claimsai.policy.domain.PolicySnapshot.PolicyStatus;
import com.claimsai.policy.domain.PolicySnapshot.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyCheckTest {

    static final Long HOLDER = 1L;
    static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    static final LocalDate TO = LocalDate.of(2027, 1, 1);

    static Optional<PolicySnapshot> auto(PolicyStatus status) {
        return Optional.of(new PolicySnapshot("POL-AUTO-1001", Product.AUTO, HOLDER, "Asha", status, FROM, TO,
                new BigDecimal("500"), Map.of(CoverageType.COLLISION, new BigDecimal("25000"))));
    }

    @Test
    void activePolicyCoveringTheLossIsVerified() {
        var result = PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.VEHICLE_COLLISION, FROM.plusDays(10), HOLDER);

        assertThat(result.verified()).isTrue();
        assertThat(result.flags()).isEmpty();
    }

    @Test
    void unknownPolicyIsFlaggedNotRefused() {
        var result = PolicyCheck.check(Optional.empty(), LossType.VEHICLE_COLLISION, FROM, HOLDER);

        assertThat(result.verified()).isFalse();
        assertThat(result.flags()).containsExactly(ClaimFlag.POLICY_NOT_FOUND);
    }

    @Test
    void theEffectiveDatesAreInclusiveStartExclusiveEnd() {
        assertThat(PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.VEHICLE_COLLISION, FROM, HOLDER).verified())
                .isTrue();
        assertThat(PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.VEHICLE_COLLISION, TO, HOLDER).flags())
                .containsExactly(ClaimFlag.POLICY_NOT_IN_FORCE);
        assertThat(PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.VEHICLE_COLLISION, FROM.minusDays(1), HOLDER)
                .flags()).containsExactly(ClaimFlag.POLICY_NOT_IN_FORCE);
    }

    @Test
    void aLapsedPolicyStillCoversALossFromWhileItRan() {
        var result = PolicyCheck.check(auto(PolicyStatus.LAPSED), LossType.VEHICLE_COLLISION, FROM.plusDays(10), HOLDER);

        assertThat(result.verified()).isTrue();
    }

    @Test
    void aCancelledPolicyIsNeverTreatedAsInForce() {
        var result = PolicyCheck.check(auto(PolicyStatus.CANCELLED), LossType.VEHICLE_COLLISION, FROM.plusDays(10), HOLDER);

        assertThat(result.flags()).containsExactly(ClaimFlag.POLICY_NOT_IN_FORCE);
    }

    @Test
    void lossWithoutTheMatchingCoverageOrProductIsNotCovered() {
        assertThat(PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.VEHICLE_THEFT, FROM.plusDays(1), HOLDER).flags())
                .containsExactly(ClaimFlag.NOT_COVERED);
        assertThat(PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.HOME_FIRE, FROM.plusDays(1), HOLDER).flags())
                .containsExactly(ClaimFlag.NOT_COVERED);
    }

    @Test
    void aPortalUserClaimingOnSomeoneElsesPolicyIsFlagged() {
        var result = PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.VEHICLE_COLLISION, FROM.plusDays(1), 99L);

        assertThat(result.flags()).containsExactly(ClaimFlag.HOLDER_MISMATCH);
    }

    @Test
    void staffTakenFnolHasNoPortalUserToCompare() {
        var result = PolicyCheck.check(auto(PolicyStatus.ACTIVE), LossType.VEHICLE_COLLISION, FROM.plusDays(1), null);

        assertThat(result.verified()).isTrue();
    }

    @Test
    void severalProblemsAreAllReported() {
        var result = PolicyCheck.check(auto(PolicyStatus.CANCELLED), LossType.VEHICLE_THEFT, FROM.plusDays(1), 99L);

        assertThat(result.flags()).containsExactlyInAnyOrder(ClaimFlag.POLICY_NOT_IN_FORCE, ClaimFlag.NOT_COVERED,
                ClaimFlag.HOLDER_MISMATCH);
    }
}
