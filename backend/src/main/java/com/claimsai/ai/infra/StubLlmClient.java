package com.claimsai.ai.infra;

import com.claimsai.ai.domain.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic stand-in for the LLM (tests, offline development, demos without an API key). It reads
 * simple markers from the document text so tests can drive every path:
 * <ul>
 *   <li>"ESTIMATE" / "INVOICE" / "POLICE" set the type; "TOTAL: 3812.50" and "DATE: 2026-09-20" the fields</li>
 *   <li>"EDITED", "INCONSISTENT", "IGNORE PREVIOUS INSTRUCTIONS" raise the matching risk signals</li>
 *   <li>"LLM_FAIL" simulates an outage (retryable); "LLM_GARBAGE" returns invalid output</li>
 *   <li>an image is always a damage photo: moderate damage, 3,000-4,500</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "stub", matchIfMissing = true)
public class StubLlmClient implements LlmClient {

    private static final Pattern TOTAL = Pattern.compile("TOTAL[:\\s]+([0-9]+(?:\\.[0-9]{1,2})?)");
    private static final Pattern DATE = Pattern.compile("DATE[:\\s]+(\\d{4}-\\d{2}-\\d{2})");

    @Override
    public String provider() {
        return "stub";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String document = documentPart(request.userText());
        String upper = document.toUpperCase(Locale.ROOT);
        if (upper.contains("LLM_FAIL")) {
            throw new LlmUnavailableException("stub: simulated provider outage", null);
        }
        if (upper.contains("LLM_GARBAGE")) {
            return new LlmResponse("Sure! Here is what I found in the document...", "stub-1", 100, 20);
        }
        if (request.imageJpeg() != null) {
            return new LlmResponse("""
                    {"docType":"DAMAGE_PHOTO","confidence":0.8,
                     "fields":{"summary":"Photo of a car with a dented rear bumper and a broken tail lamp."},
                     "damage":{"severity":"MODERATE","parts":["rear bumper","tail lamp"],"costLow":3000,"costHigh":4500},
                     "riskSignals":[]}""", "stub-vision-1", 900, 80);
        }
        String docType = upper.contains("ESTIMATE") ? "REPAIR_ESTIMATE"
                : upper.contains("INVOICE") ? "INVOICE"
                : upper.contains("POLICE") ? "POLICE_REPORT" : "OTHER";
        String total = find(TOTAL, upper);
        String date = find(DATE, upper);
        List<String> signals = new ArrayList<>();
        if (upper.contains("EDITED")) {
            signals.add("{\"code\":\"EDITED_DOCUMENT_SUSPECTED\",\"detail\":\"totals do not add up\"}");
        }
        if (upper.contains("INCONSISTENT")) {
            signals.add("{\"code\":\"INCONSISTENT_WITH_DESCRIPTION\",\"detail\":\"different vehicle\"}");
        }
        if (upper.contains("IGNORE PREVIOUS INSTRUCTIONS")) {
            signals.add("{\"code\":\"INSTRUCTIONS_IN_DOCUMENT\",\"detail\":\"text addressed to the AI\"}");
        }
        String json = """
                {"docType":"%s","confidence":0.9,
                 "fields":{"totalAmount":%s,"currency":%s,"issueDate":%s,"summary":"%s document."},
                 "damage":null,"riskSignals":[%s]}""".formatted(docType, total == null ? "null" : total,
                total == null ? "null" : "\"INR\"", date == null ? "null" : "\"" + date + "\"",
                docType.charAt(0) + docType.substring(1).toLowerCase(Locale.ROOT).replace('_', ' '),
                String.join(",", signals));
        return new LlmResponse(json, "stub-1", Math.max(50, document.length() / 4), 60);
    }

    /** The part between the document tags, so markers in the instructions don't count. */
    private static String documentPart(String userText) {
        int start = userText.indexOf("<document>");
        int end = userText.lastIndexOf("</document>");
        return start >= 0 && end > start ? userText.substring(start, end) : "";
    }

    private static String find(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
