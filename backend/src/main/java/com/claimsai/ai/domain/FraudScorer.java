package com.claimsai.ai.domain;

import com.claimsai.ai.domain.DocumentExtraction.DocType;
import com.claimsai.ai.domain.DocumentExtraction.RiskSignalCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Fraud score 0-100 (design section 11.3). Mostly deterministic rules on facts; the LLM contributes only
 * named, bounded signals. Every point has a reason the adjuster and SIU can read, so the score is
 * explainable and a human can disagree with it.
 */
public final class FraudScorer {

    public enum Rule {
        LOSS_SOON_AFTER_POLICY_START(25),
        FREQUENT_CLAIMS_ON_POLICY(20),
        DUPLICATE_DOCUMENT_ON_OTHER_CLAIM(30),
        DOCUMENT_DATED_BEFORE_LOSS(15),
        AMOUNT_ABOVE_DAMAGE_ESTIMATE(15),
        // LLM signals, capped weights
        EDITED_DOCUMENT_SUSPECTED(15),
        INCONSISTENT_WITH_DESCRIPTION(20),
        INSTRUCTIONS_IN_DOCUMENT(10);

        public final int points;

        Rule(int points) {
            this.points = points;
        }
    }

    private static final Map<RiskSignalCode, Rule> LLM_RULES = new EnumMap<>(Map.of(
            RiskSignalCode.EDITED_DOCUMENT_SUSPECTED, Rule.EDITED_DOCUMENT_SUSPECTED,
            RiskSignalCode.INCONSISTENT_WITH_DESCRIPTION, Rule.INCONSISTENT_WITH_DESCRIPTION,
            RiskSignalCode.INSTRUCTIONS_IN_DOCUMENT, Rule.INSTRUCTIONS_IN_DOCUMENT));

    private FraudScorer() {
    }

    /**
     * @param policyStart          effective date of the policy, if known
     * @param otherClaimsOnPolicy  claims on the same policy in the 12 months before this one (this one excluded)
     * @param duplicateDocuments   documents on this claim whose exact bytes are also on another claim
     * @param claimedAmount        the reporter's estimate, may be null
     * @param extractions          validated (and, where a person overrode them, corrected) document results
     */
    public record Facts(LocalDate lossDate, LocalDate policyStart, long otherClaimsOnPolicy, long duplicateDocuments,
                        BigDecimal claimedAmount, List<DocumentExtraction> extractions) {
    }

    public record Reason(Rule rule, int points, String detail) {
    }

    public record Score(int value, List<Reason> reasons) {
    }

    public static Score score(Facts facts) {
        List<Reason> reasons = new ArrayList<>();
        if (facts.policyStart() != null && !facts.lossDate().isBefore(facts.policyStart())
                && facts.lossDate().isBefore(facts.policyStart().plusDays(30))) {
            reasons.add(reason(Rule.LOSS_SOON_AFTER_POLICY_START, "loss " + facts.lossDate()
                    + " within 30 days of policy start " + facts.policyStart()));
        }
        if (facts.otherClaimsOnPolicy() >= 2) {
            reasons.add(reason(Rule.FREQUENT_CLAIMS_ON_POLICY, facts.otherClaimsOnPolicy()
                    + " other claims on this policy in 12 months"));
        }
        if (facts.duplicateDocuments() > 0) {
            reasons.add(reason(Rule.DUPLICATE_DOCUMENT_ON_OTHER_CLAIM, facts.duplicateDocuments()
                    + " document(s) identical to one on another claim"));
        }
        facts.extractions().stream()
                .filter(e -> e.docType() == DocType.REPAIR_ESTIMATE || e.docType() == DocType.INVOICE)
                .filter(e -> e.issueDate() != null && e.issueDate().isBefore(facts.lossDate()))
                .findFirst()
                .ifPresent(e -> reasons.add(reason(Rule.DOCUMENT_DATED_BEFORE_LOSS, e.docType() + " dated "
                        + e.issueDate() + ", before the loss on " + facts.lossDate())));

        BigDecimal damageHigh = facts.extractions().stream()
                .filter(e -> e.damage() != null && e.damage().costHigh() != null)
                .map(e -> e.damage().costHigh()).max(BigDecimal::compareTo).orElse(null);
        BigDecimal claimed = highestClaimedAmount(facts);
        if (damageHigh != null && claimed != null && claimed.compareTo(damageHigh.multiply(new BigDecimal("1.5"))) > 0) {
            reasons.add(reason(Rule.AMOUNT_ABOVE_DAMAGE_ESTIMATE, "claimed " + claimed
                    + " is more than 1.5 x the assessed damage of at most " + damageHigh));
        }

        // each LLM signal kind counts once, however many documents raise it
        facts.extractions().stream()
                .flatMap(e -> e.riskSignals().stream())
                .filter(s -> LLM_RULES.containsKey(s.code()))
                .map(s -> LLM_RULES.get(s.code()))
                .distinct()
                .forEach(rule -> reasons.add(reason(rule, "raised by the document assessment")));

        int total = Math.min(100, reasons.stream().mapToInt(Reason::points).sum());
        return new Score(total, List.copyOf(reasons));
    }

    /** The highest amount asked for: the reporter's estimate, or an estimate/invoice total. */
    private static BigDecimal highestClaimedAmount(Facts facts) {
        BigDecimal claimed = facts.claimedAmount();
        for (DocumentExtraction e : facts.extractions()) {
            if ((e.docType() == DocType.REPAIR_ESTIMATE || e.docType() == DocType.INVOICE) && e.totalAmount() != null
                    && (claimed == null || e.totalAmount().compareTo(claimed) > 0)) {
                claimed = e.totalAmount();
            }
        }
        return claimed;
    }

    private static Reason reason(Rule rule, String detail) {
        return new Reason(rule, rule.points, detail);
    }
}
