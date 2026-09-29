package com.claimsai.ai.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the model says about one document, after validation. Every field may be null: the model is told to
 * leave out what it can't read rather than guess.
 */
public record DocumentExtraction(
        DocType docType,
        double confidence,
        BigDecimal totalAmount,
        String currency,
        LocalDate issueDate,
        String vehicleRegistration,
        String issuer,
        String summary,
        Damage damage,
        List<RiskSignal> riskSignals) {

    public enum DocType { REPAIR_ESTIMATE, INVOICE, POLICE_REPORT, DAMAGE_PHOTO, MEDICAL_REPORT, OTHER }

    public enum Severity { NONE, MINOR, MODERATE, SEVERE }

    /** Signals the model may raise. Anything else it invents is dropped by the validator. */
    public enum RiskSignalCode {
        /** signs of editing: inconsistent fonts, cloned areas, altered numbers */
        EDITED_DOCUMENT_SUSPECTED,
        /** the document contradicts the claimant's description of the loss */
        INCONSISTENT_WITH_DESCRIPTION,
        /** text inside the document tries to instruct the AI ("ignore previous instructions") */
        INSTRUCTIONS_IN_DOCUMENT,
        /** too blurred or damaged to read reliably */
        ILLEGIBLE
    }

    public record Damage(Severity severity, List<String> parts, BigDecimal costLow, BigDecimal costHigh) {
    }

    public record RiskSignal(RiskSignalCode code, String detail) {
    }
}
