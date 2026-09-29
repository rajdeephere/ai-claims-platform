package com.claimsai.ai.domain;

import com.claimsai.ai.domain.DocumentExtraction.Damage;
import com.claimsai.ai.domain.DocumentExtraction.DocType;
import com.claimsai.ai.domain.DocumentExtraction.RiskSignal;
import com.claimsai.ai.domain.DocumentExtraction.RiskSignalCode;
import com.claimsai.ai.domain.DocumentExtraction.Severity;
import com.claimsai.ai.domain.FraudScorer.Rule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScorerTest {

    static final LocalDate LOSS = LocalDate.of(2026, 9, 20);

    static DocumentExtraction estimate(String total, LocalDate issued, RiskSignalCode... signals) {
        return new DocumentExtraction(DocType.REPAIR_ESTIMATE, 0.9, total == null ? null : new BigDecimal(total), "INR",
                issued, null, null, null, null,
                java.util.Arrays.stream(signals).map(c -> new RiskSignal(c, "x")).toList());
    }

    static DocumentExtraction photo(String high) {
        return new DocumentExtraction(DocType.DAMAGE_PHOTO, 0.8, null, null, null, null, null, null,
                new Damage(Severity.MODERATE, List.of("bumper"), new BigDecimal("1000"), new BigDecimal(high)), List.of());
    }

    static FraudScorer.Score score(LocalDate policyStart, long otherClaims, long duplicates, String claimed,
                                   DocumentExtraction... extractions) {
        return FraudScorer.score(new FraudScorer.Facts(LOSS, policyStart, otherClaims, duplicates,
                claimed == null ? null : new BigDecimal(claimed), List.of(extractions)));
    }

    @Test
    void anOrdinaryClaimScoresZero() {
        FraudScorer.Score s = score(LocalDate.of(2026, 1, 1), 0, 0, "3800",
                estimate("3800", LOSS.plusDays(2)), photo("4500"));

        assertThat(s.value()).isZero();
        assertThat(s.reasons()).isEmpty();
    }

    @Test
    void eachRuleAddsItsPointsWithAReadableReason() {
        FraudScorer.Score s = score(LOSS.minusDays(10), 2, 1, null, estimate("9000", LOSS.minusDays(3)), photo("4000"));

        assertThat(s.reasons()).extracting(FraudScorer.Reason::rule).containsExactlyInAnyOrder(
                Rule.LOSS_SOON_AFTER_POLICY_START, Rule.FREQUENT_CLAIMS_ON_POLICY, Rule.DUPLICATE_DOCUMENT_ON_OTHER_CLAIM,
                Rule.DOCUMENT_DATED_BEFORE_LOSS, Rule.AMOUNT_ABOVE_DAMAGE_ESTIMATE);
        assertThat(s.value()).isEqualTo(100);   // 25 + 20 + 30 + 15 + 15 = 105, capped
        assertThat(s.reasons()).filteredOn(r -> r.rule() == Rule.DOCUMENT_DATED_BEFORE_LOSS).singleElement()
                .satisfies(r -> assertThat(r.detail()).contains("before the loss on 2026-09-20"));
    }

    @Test
    void theBoundariesAreExact() {
        assertThat(score(LOSS.minusDays(30), 0, 0, null).value()).as("30 days after start").isZero();
        assertThat(score(LOSS.minusDays(29), 0, 0, null).value()).isEqualTo(25);
        assertThat(score(null, 1, 0, null).value()).as("one other claim is normal").isZero();
        assertThat(score(null, 0, 0, "6750", photo("4500")).value()).as("exactly 1.5x").isZero();
        assertThat(score(null, 0, 0, "6751", photo("4500")).value()).isEqualTo(15);
    }

    @Test
    void llmSignalsCountOnceEachHoweverManyDocumentsRaiseThem() {
        FraudScorer.Score s = score(null, 0, 0, null,
                estimate("100", LOSS, RiskSignalCode.EDITED_DOCUMENT_SUSPECTED),
                estimate("200", LOSS, RiskSignalCode.EDITED_DOCUMENT_SUSPECTED, RiskSignalCode.INSTRUCTIONS_IN_DOCUMENT),
                estimate("300", LOSS, RiskSignalCode.ILLEGIBLE));

        assertThat(s.value()).isEqualTo(15 + 10);
        assertThat(s.reasons()).extracting(FraudScorer.Reason::rule)
                .containsExactlyInAnyOrder(Rule.EDITED_DOCUMENT_SUSPECTED, Rule.INSTRUCTIONS_IN_DOCUMENT);
    }

    @Test
    void aPhotoDatedBeforeTheLossIsNotSuspiciousOnlyEstimatesAndInvoicesAre() {
        DocumentExtraction oldReport = new DocumentExtraction(DocType.POLICE_REPORT, 0.9, null, null, LOSS.minusDays(5),
                null, null, null, null, List.of());

        assertThat(score(null, 0, 0, null, oldReport).value()).isZero();
    }
}
