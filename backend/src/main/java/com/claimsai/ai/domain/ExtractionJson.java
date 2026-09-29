package com.claimsai.ai.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An extraction as stored (the prompt's JSON shape), and back. Reading goes through the validator again,
 * so stored data and a person's corrections obey exactly the same rules as the model's answer.
 */
public final class ExtractionJson {

    /** What an adjuster may correct. */
    public static final Set<String> OVERRIDABLE = Set.of("docType", "totalAmount", "currency", "issueDate", "severity",
            "costLow", "costHigh");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ExtractionJson() {
    }

    public static Map<String, Object> toMap(DocumentExtraction e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("totalAmount", e.totalAmount());
        fields.put("currency", e.currency());
        fields.put("issueDate", e.issueDate() == null ? null : e.issueDate().toString());
        fields.put("vehicleRegistration", e.vehicleRegistration());
        fields.put("issuer", e.issuer());
        fields.put("summary", e.summary());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("docType", e.docType().name());
        root.put("confidence", e.confidence());
        root.put("fields", fields);
        if (e.damage() != null) {
            Map<String, Object> damage = new LinkedHashMap<>();
            damage.put("severity", e.damage().severity() == null ? null : e.damage().severity().name());
            damage.put("parts", e.damage().parts());
            damage.put("costLow", e.damage().costLow());
            damage.put("costHigh", e.damage().costHigh());
            root.put("damage", damage);
        } else {
            root.put("damage", null);
        }
        root.put("riskSignals", e.riskSignals().stream()
                .map(s -> Map.of("code", s.code().name(), "detail", s.detail())).toList());
        return root;
    }

    /** The stored output with a person's corrections applied on top, validated again. */
    @SuppressWarnings("unchecked")
    public static ExtractionValidator.Result effective(Map<String, Object> output, Map<String, Object> override) {
        Map<String, Object> merged = deepCopy(output);
        if (override != null) {
            Map<String, Object> fields = (Map<String, Object>) merged.computeIfAbsent("fields", k -> new LinkedHashMap<>());
            override.forEach((key, value) -> {
                switch (key) {
                    case "docType" -> merged.put("docType", value);
                    case "totalAmount", "currency", "issueDate" -> fields.put(key, value);
                    case "severity", "costLow", "costHigh" -> {
                        Map<String, Object> damage = merged.get("damage") instanceof Map<?, ?> d
                                ? (Map<String, Object>) d : new LinkedHashMap<>(Map.of("parts", List.of()));
                        damage.put(key, value);
                        merged.put("damage", damage);
                    }
                    default -> { /* not overridable: ignored, callers check OVERRIDABLE first */ }
                }
            });
        }
        try {
            // no loss-date window here: a person may knowingly record an old date
            return ExtractionValidator.validate(JSON.writeValueAsString(merged), null, LocalDate.MAX);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        try {
            return JSON.readValue(JSON.writeValueAsString(source), LinkedHashMap.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
