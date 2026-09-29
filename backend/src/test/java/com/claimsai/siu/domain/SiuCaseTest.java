package com.claimsai.siu.domain;

import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiuCaseTest {

    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    static final Long INVESTIGATOR = 6L;

    @Test
    void aRuleReferralHasNoReferrerAManualOneAlwaysHas() {
        assertThat(SiuCase.open(1L, SiuCase.Source.RULE, "score 80", null, 80, NOW).getReferredBy()).isNull();
        assertThatThrownBy(() -> SiuCase.open(1L, SiuCase.Source.MANUAL, "x", null, 10, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SiuCase.open(1L, SiuCase.Source.RULE, "x", 3L, 10, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOutcomeNeedsFindingsAndIsRecordedOnce() {
        SiuCase c = SiuCase.open(1L, SiuCase.Source.MANUAL, "garage invoice looks reused", 3L, 40, NOW);

        assertThatThrownBy(() -> c.decide(SiuCase.Outcome.CLEARED, INVESTIGATOR, " ", NOW))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("FINDINGS_REQUIRED");
        c.decide(SiuCase.Outcome.CONFIRMED, INVESTIGATOR, "invoice reused from another claim", NOW);

        assertThat(c.getStatus()).isEqualTo(SiuCase.Status.CONFIRMED);
        assertThat(c.isOpen()).isFalse();
        assertThatThrownBy(() -> c.decide(SiuCase.Outcome.CLEARED, INVESTIGATOR, "second thoughts", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("ALREADY_DECIDED");
    }
}
