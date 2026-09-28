package com.claimsai.claim.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.claimsai.claim.domain.ClaimStatus.ASSESSING;
import static com.claimsai.claim.domain.ClaimStatus.AWAITING_INFO;
import static com.claimsai.claim.domain.ClaimStatus.CLOSED;
import static com.claimsai.claim.domain.ClaimStatus.OPEN;
import static com.claimsai.claim.domain.ClaimStatus.SIU_REVIEW;
import static com.claimsai.claim.domain.ClaimStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every one of the 36 (from, to) pairs, allowed or not, against the lifecycle in the design document. If
 * someone adds a transition, this test makes them say so explicitly.
 */
class ClaimStatusTest {

    private static final Map<ClaimStatus, Set<ClaimStatus>> DESIGN = Map.of(
            SUBMITTED, Set.of(ASSESSING),
            ASSESSING, Set.of(OPEN, SIU_REVIEW),
            OPEN, Set.of(AWAITING_INFO, SIU_REVIEW, CLOSED),
            AWAITING_INFO, Set.of(OPEN, CLOSED),
            SIU_REVIEW, Set.of(OPEN),
            CLOSED, Set.of(OPEN));

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (ClaimStatus from : ClaimStatus.values()) {
            for (ClaimStatus to : ClaimStatus.values()) {
                pairs.add(Arguments.of(from, to, DESIGN.get(from).contains(to)));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @MethodSource("allPairs")
    void transitionTableMatchesTheDesign(ClaimStatus from, ClaimStatus to, boolean allowed) {
        assertThat(from.canMoveTo(to)).isEqualTo(allowed);
    }
}
