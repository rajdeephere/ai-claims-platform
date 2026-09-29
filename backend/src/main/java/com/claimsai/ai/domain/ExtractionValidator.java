package com.claimsai.ai.domain;

import com.claimsai.ai.domain.DocumentExtraction.Damage;
import com.claimsai.ai.domain.DocumentExtraction.DocType;
import com.claimsai.ai.domain.DocumentExtraction.RiskSignal;
import com.claimsai.ai.domain.DocumentExtraction.RiskSignalCode;
import com.claimsai.ai.domain.DocumentExtraction.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the model's raw answer into a {@link DocumentExtraction}, or a list of precise errors (ADR-0021).
 * The model's output is untrusted input: it is parsed, typed and range-checked like any request body.
 * <ul>
 *   <li>structure: one JSON object, known enums, numbers where numbers belong</li>
 *   <li>ranges: amounts 0..10,000,000 with at most 2 decimals, confidence 0..1, cost low <= high</li>
 *   <li>dates: not in the future, not more than a year before the loss</li>
 *   <li>sizes: bounded lists and strings, so a runaway answer can't bloat the database</li>
 *   <li>unknown risk-signal codes are dropped, not trusted</li>
 * </ul>
 */
public final class ExtractionValidator {

    static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractionValidator() {
    }

    public record Result(DocumentExtraction extraction, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public static Result validate(String raw, LocalDate lossDate, LocalDate today) {
        List<String> errors = new ArrayList<>();
        JsonNode root;
        try {
            root = JSON.readTree(stripCodeFence(raw));
        } catch (Exception e) {
            return new Result(null, List.of("the answer is not valid JSON"));
        }
        if (root == null || !root.isObject()) {
            return new Result(null, List.of("the answer must be one JSON object"));
        }

        DocType docType = enumValue(root.path("docType"), DocType.class, "docType", errors, true);
        double confidence = root.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1) {
            errors.add("confidence must be a number between 0 and 1");
        }

        JsonNode fields = root.path("fields");
        BigDecimal total = amount(fields.path("totalAmount"), "fields.totalAmount", errors);
        String currency = text(fields.path("currency"), 3, "fields.currency", errors);
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            errors.add("fields.currency must be a 3-letter ISO code");
        }
        LocalDate issueDate = date(fields.path("issueDate"), lossDate, today, errors);
        String registration = text(fields.path("vehicleRegistration"), 20, "fields.vehicleRegistration", errors);
        String issuer = text(fields.path("issuer"), 120, "fields.issuer", errors);
        String summary = text(fields.path("summary"), 500, "fields.summary", errors);

        Damage damage = null;
        JsonNode d = root.path("damage");
        if (d.isObject()) {
            Severity severity = enumValue(d.path("severity"), Severity.class, "damage.severity", errors, true);
            List<String> parts = new ArrayList<>();
            if (d.path("parts").isArray()) {
                for (JsonNode part : d.path("parts")) {
                    if (parts.size() < 20 && part.isTextual() && !part.asText().isBlank()) {
                        parts.add(truncate(part.asText().strip(), 60));
                    }
                }
            }
            BigDecimal low = amount(d.path("costLow"), "damage.costLow", errors);
            BigDecimal high = amount(d.path("costHigh"), "damage.costHigh", errors);
            if (low != null && high != null && low.compareTo(high) > 0) {
                errors.add("damage.costLow must not be greater than damage.costHigh");
            }
            damage = new Damage(severity, List.copyOf(parts), low, high);
        } else if (!d.isMissingNode() && !d.isNull()) {
            errors.add("damage must be an object or null");
        }

        List<RiskSignal> signals = new ArrayList<>();
        if (root.path("riskSignals").isArray()) {
            for (JsonNode s : root.path("riskSignals")) {
                RiskSignalCode code = enumValue(s.path("code"), RiskSignalCode.class, "riskSignals.code", null, false);
                if (code != null && signals.size() < 10) {
                    signals.add(new RiskSignal(code, truncate(s.path("detail").asText(""), 200)));
                }
            }
        }

        if (!errors.isEmpty()) {
            return new Result(null, List.copyOf(errors));
        }
        return new Result(new DocumentExtraction(docType, confidence, total, currency, issueDate, registration, issuer,
                summary, damage, List.copyOf(signals)), List.of());
    }

    /** Some models wrap JSON in ```json fences despite being told not to. */
    static String stripCodeFence(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int end = s.lastIndexOf("```");
            if (end >= 0) {
                s = s.substring(0, end);
            }
        }
        return s.strip();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode node, Class<E> type, String name, List<String> errors,
                                                   boolean required) {
        if (node.isMissingNode() || node.isNull()) {
            if (required && errors != null) {
                errors.add(name + " is required");
            }
            return null;
        }
        try {
            return Enum.valueOf(type, node.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (errors != null) {
                errors.add(name + " must be one of " + java.util.Arrays.toString(type.getEnumConstants()));
            }
            return null;
        }
    }

    private static BigDecimal amount(JsonNode node, String name, List<String> errors) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            errors.add(name + " must be a number");
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0) {
            errors.add(name + " must be between 0 and " + MAX_AMOUNT.toPlainString());
            return null;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate date(JsonNode node, LocalDate lossDate, LocalDate today, List<String> errors) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(node.asText());
            if (date.isAfter(today)) {
                errors.add("fields.issueDate is in the future");
            } else if (lossDate != null && date.isBefore(lossDate.minusYears(1))) {
                errors.add("fields.issueDate is more than a year before the loss");
            }
            return date;
        } catch (DateTimeParseException e) {
            errors.add("fields.issueDate must be YYYY-MM-DD");
            return null;
        }
    }

    private static String text(JsonNode node, int max, String name, List<String> errors) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            errors.add(name + " must be a string");
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() ? null : truncate(value, max);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
