package com.claimsai.claim.domain;

import com.claimsai.claim.domain.ClaimEnums.Segment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TriageRulesTest {

    static final TriageRules.Thresholds T = new TriageRules.Thresholds(new BigDecimal("2000.00"), 20,
            new BigDecimal("25000.00"), 70);

    static TriageRules.Decision decide(String estimate, boolean injuries, boolean verified, Integer fraud) {
        return TriageRules.decide(new TriageRules.Input(estimate == null ? null : new BigDecimal(estimate), injuries,
                verified, fraud), T);
    }

    @ParameterizedTest(name = "estimate={0} injuries={1} verified={2} fraud={3} -> {4}")
    @CsvSource(nullValues = "null", value = {
            // fast track: small, low risk, verified, no injury
            "1999.99, false, true,  19,   FAST_TRACK",
            // the boundaries are exclusive
            "2000.00, false, true,  19,   STANDARD",
            "1500.00, false, true,  20,   STANDARD",
            // never fast-track without a risk assessment
            "1500.00, false, true,  null, STANDARD",
            "null,    false, true,  5,    STANDARD",
            // complex wins over everything else
            "1500.00, true,  true,  5,    COMPLEX",
            "1500.00, false, false, 5,    COMPLEX",
            "25000.01, false, true, 5,    COMPLEX",
            "25000.00, false, true, 5,    STANDARD",
    })
    void segments(String estimate, boolean injuries, boolean verified, Integer fraud, Segment expected) {
        assertThat(decide(estimate, injuries, verified, fraud).segment()).isEqualTo(expected);
    }

    @Test
    void siuReferralFromTheThresholdUpAndTheSegmentIsStillDecided() {
        assertThat(decide("1000", false, true, 69).referToSiu()).isFalse();

        TriageRules.Decision decision = decide("1000", true, true, 70);
        assertThat(decision.referToSiu()).isTrue();
        assertThat(decision.segment()).isEqualTo(Segment.COMPLEX);
    }

    @Test
    void theDecisionExplainsItself() {
        assertThat(decide("1000", true, true, 5).reason()).isEqualTo("injuries reported");
        assertThat(decide("1000", false, true, null).reason()).isEqualTo("no risk assessment yet");
    }
}
