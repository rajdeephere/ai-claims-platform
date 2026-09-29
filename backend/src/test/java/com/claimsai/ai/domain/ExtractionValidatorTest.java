package com.claimsai.ai.domain;

import com.claimsai.ai.domain.DocumentExtraction.DocType;
import com.claimsai.ai.domain.DocumentExtraction.RiskSignalCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionValidatorTest {

    static final LocalDate LOSS = LocalDate.of(2026, 9, 20);
    static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    static ExtractionValidator.Result validate(String json) {
        return ExtractionValidator.validate(json, LOSS, TODAY);
    }

    static final String GOOD = """
            {"docType":"REPAIR_ESTIMATE","confidence":0.91,
             "fields":{"totalAmount":3812.5,"currency":"INR","issueDate":"2026-09-22","issuer":"City Motors",
                       "summary":"Estimate for rear bumper"},
             "damage":{"severity":"moderate","parts":["rear bumper"],"costLow":3200,"costHigh":4400},
             "riskSignals":[{"code":"EDITED_DOCUMENT_SUSPECTED","detail":"totals"},{"code":"MADE_UP","detail":"x"}]}""";

    @Test
    void aWellFormedAnswerBecomesATypedExtraction() {
        ExtractionValidator.Result result = validate(GOOD);

        assertThat(result.valid()).isTrue();
        DocumentExtraction e = result.extraction();
        assertThat(e.docType()).isEqualTo(DocType.REPAIR_ESTIMATE);
        assertThat(e.totalAmount()).hasToString("3812.50");
        assertThat(e.issueDate()).isEqualTo(LocalDate.of(2026, 9, 22));
        assertThat(e.damage().severity()).isEqualTo(DocumentExtraction.Severity.MODERATE);   // case-insensitive
        // a signal code the model invented is dropped, never trusted
        assertThat(e.riskSignals()).extracting(DocumentExtraction.RiskSignal::code)
                .containsExactly(RiskSignalCode.EDITED_DOCUMENT_SUSPECTED);
    }

    @Test
    void codeFencesAroundTheJsonAreTolerated() {
        assertThat(validate("```json\n" + GOOD + "\n```").valid()).isTrue();
    }

    @Test
    void proseInsteadOfJsonIsRejected() {
        assertThat(validate("Sure! The estimate is 3812.50.").errors()).containsExactly("the answer is not valid JSON");
        assertThat(validate("[1,2]").errors()).containsExactly("the answer must be one JSON object");
    }

    @Test
    void eachProblemIsReportedPreciselySoTheRetryCanFixIt() {
        ExtractionValidator.Result result = validate("""
                {"docType":"SELFIE","confidence":7,
                 "fields":{"totalAmount":"3,812","currency":"rupees","issueDate":"2027-01-01"},
                 "damage":{"severity":"MINOR","costLow":900,"costHigh":100}}""");

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anySatisfy(e -> assertThat(e).startsWith("docType must be one of"))
                .contains("confidence must be a number between 0 and 1",
                        "fields.totalAmount must be a number",
                        "fields.currency must be a 3-letter ISO code",
                        "fields.issueDate is in the future",
                        "damage.costLow must not be greater than damage.costHigh");
    }

    @Test
    void absurdAmountsAndAncientDatesAreRefused() {
        ExtractionValidator.Result result = validate("""
                {"docType":"INVOICE","confidence":0.5,
                 "fields":{"totalAmount":99999999999,"issueDate":"2020-01-01"}}""");

        assertThat(result.errors()).contains("fields.totalAmount must be between 0 and 10000000",
                "fields.issueDate is more than a year before the loss");
    }

    @Test
    void nullsAreTheNormalAnswerForUnreadableFields() {
        ExtractionValidator.Result result = validate("""
                {"docType":"OTHER","confidence":0.2,"fields":{"totalAmount":null,"issueDate":null},"damage":null,
                 "riskSignals":[]}""");

        assertThat(result.valid()).isTrue();
        assertThat(result.extraction().totalAmount()).isNull();
        assertThat(result.extraction().damage()).isNull();
    }

    @Test
    void storedResultsAndCorrectionsGoThroughTheSameRules() {
        Map<String, Object> stored = ExtractionJson.toMap(validate(GOOD).extraction());

        assertThat(ExtractionJson.effective(stored, null).extraction().totalAmount()).hasToString("3812.50");
        assertThat(ExtractionJson.effective(stored, Map.of("totalAmount", 3500, "severity", "MINOR")).extraction())
                .satisfies(e -> {
                    assertThat(e.totalAmount()).hasToString("3500.00");
                    assertThat(e.damage().severity()).isEqualTo(DocumentExtraction.Severity.MINOR);
                });
        assertThat(ExtractionJson.effective(stored, Map.of("totalAmount", -1)).errors())
                .contains("fields.totalAmount must be between 0 and 10000000");
    }
}
