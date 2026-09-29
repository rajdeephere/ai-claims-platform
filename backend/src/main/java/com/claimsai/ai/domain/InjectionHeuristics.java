package com.claimsai.ai.domain;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic backstop for prompt-injection detection. The prompt asks the model to report text that
 * addresses the AI, but in a live test with Groq the model resisted an injection without reporting it
 * (BUG-009). A model can't be trusted to report attacks on itself, so known phrasings are also caught by
 * plain rules on the extracted text. False positives only add a reviewable signal; nothing is denied.
 */
public final class InjectionHeuristics {

    private static final List<Pattern> PATTERNS = List.of(
            "(ignore|disregard|forget)\\s+(all\\s+|any\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)",
            "(note|message|instructions?)\\s+(to|for)\\s+(the\\s+)?(ai|automated|claims\\s+system|assistant|model|llm)",
            "you\\s+are\\s+(now\\s+)?(an?\\s+)?(ai|assistant|language\\s+model|chatbot)",
            "system\\s+prompt",
            "(mark|set|flag|treat)\\s+(it|this|the)?\\s*(claim\\s+)?(as\\s+)?(approved|verified|genuine|low[\\s-]risk)",
            "(no|without)\\s+(any\\s+)?risk\\s+signals?")
            .stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();

    private InjectionHeuristics() {
    }

    /** The first suspicious phrase found in the document text, if any. */
    public static Optional<String> find(String documentText) {
        if (documentText == null || documentText.isBlank()) {
            return Optional.empty();
        }
        String text = documentText.replaceAll("\\s+", " ");
        for (Pattern pattern : PATTERNS) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                return Optional.of(m.group());
            }
        }
        return Optional.empty();
    }
}
