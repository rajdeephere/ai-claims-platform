package com.claimsai.ai.infra;

import com.claimsai.ai.app.AiProperties;
import com.claimsai.ai.domain.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groq's OpenAI-compatible chat completions API ({@code POST /chat/completions}). Text documents go to the
 * text model; images (photos, rendered scan pages) to the vision model as a base64 data URL. JSON mode and
 * temperature 0 for stable, parseable answers. Nothing but ids, model, token counts and latency is logged:
 * document content is personal data.
 *
 * <p>Created only when {@code app.ai.provider=groq} (see AiConfig).
 */
public class GroqLlmClient implements LlmClient {

    private final RestClient http;
    private final AiProperties.Groq settings;
    private final ObjectMapper json;

    public GroqLlmClient(RestClient http, AiProperties.Groq settings, ObjectMapper json) {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new IllegalStateException("app.ai.provider=groq needs GROQ_API_KEY");
        }
        this.http = http;
        this.settings = settings;
        this.json = json;
    }

    @Override
    public String provider() {
        return "groq";
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        String model = request.imageJpeg() != null ? settings.visionModel() : settings.textModel();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", request.maxTokens());
        if (request.jsonOutput()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", request.system()),
                Map.of("role", "user", "content", userContent(request))));
        try {
            return http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .body(body)
                    .exchange((req, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        String text = new String(response.getBody().readAllBytes());
                        if (status.value() == 429) {
                            throw new LlmRateLimitedException("Groq rate limit (retry-after "
                                    + response.getHeaders().getFirst("retry-after") + ")");
                        }
                        if (status.is5xxServerError()) {
                            throw new LlmUnavailableException("Groq " + status.value(), null);
                        }
                        if (status.isError()) {
                            throw new LlmRejectedException("Groq refused the request: " + status.value() + " "
                                    + excerpt(text));
                        }
                        return parse(text, model);
                    });
        } catch (ResourceAccessException e) {
            throw new LlmUnavailableException("Groq unreachable or timed out: " + e.getMessage(), e);
        }
    }

    private Object userContent(LlmRequest request) {
        if (request.imageJpeg() == null) {
            return request.userText();
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", request.userText()));
        parts.add(Map.of("type", "image_url", "image_url",
                Map.of("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(request.imageJpeg()))));
        return parts;
    }

    private LlmResponse parse(String text, String requestedModel) throws IOException {
        JsonNode root = json.readTree(text);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new LlmUnavailableException("Groq answer has no message content", null);
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(content.asText(), root.path("model").asText(requestedModel),
                usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null,
                usage.hasNonNull("completion_tokens") ? usage.get("completion_tokens").asInt() : null);
    }

    private static String excerpt(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200);
    }
}
