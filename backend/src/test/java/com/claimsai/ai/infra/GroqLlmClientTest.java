package com.claimsai.ai.infra;

import com.claimsai.ai.app.AiProperties;
import com.claimsai.ai.domain.LlmClient.LlmRateLimitedException;
import com.claimsai.ai.domain.LlmClient.LlmRejectedException;
import com.claimsai.ai.domain.LlmClient.LlmRequest;
import com.claimsai.ai.domain.LlmClient.LlmResponse;
import com.claimsai.ai.domain.LlmClient.LlmUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The HTTP contract with Groq, against a mock server: what we send, and how each answer is classified. */
class GroqLlmClientTest {

    static final AiProperties.Groq SETTINGS = new AiProperties.Groq(URI.create("https://groq.test/openai/v1"),
            "test-key", "text-model", "vision-model", Duration.ofSeconds(5));

    private MockRestServiceServer server;
    private GroqLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(SETTINGS.baseUrl().toString());
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GroqLlmClient(builder.build(), SETTINGS, new ObjectMapper());
    }

    static final String OK = """
            {"model":"text-model","choices":[{"message":{"role":"assistant","content":"{\\"docType\\":\\"OTHER\\"}"}}],
             "usage":{"prompt_tokens":321,"completion_tokens":45}}""";

    @Test
    void aTextRequestUsesTheTextModelJsonModeAndTemperatureZero() {
        server.expect(requestTo("https://groq.test/openai/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("text-model"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("read this"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        LlmResponse response = client.complete(new LlmRequest("rules", "read this", null, true, 500));

        assertThat(response.content()).isEqualTo("{\"docType\":\"OTHER\"}");
        assertThat(response.tokensIn()).isEqualTo(321);
        assertThat(response.tokensOut()).isEqualTo(45);
        server.verify();
    }

    @Test
    void anImageGoesToTheVisionModelAsABase64DataUrl() {
        server.expect(jsonPath("$.model").value("vision-model"))
                .andExpect(jsonPath("$.messages[1].content[1].type").value("image_url"))
                .andExpect(jsonPath("$.messages[1].content[1].image_url.url").value("data:image/jpeg;base64,AQID"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.complete(new LlmRequest("rules", "look", new byte[]{1, 2, 3}, true, 500));

        server.verify();
    }

    @Test
    void eachFailureIsClassifiedSoTheJobKnowsWhetherRetryingHelps() {
        server.expect(requestTo("https://groq.test/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("retry-after", "7"));
        assertThatThrownBy(() -> client.complete(new LlmRequest("s", "u", null, true, 10)))
                .isInstanceOf(LlmRateLimitedException.class).hasMessageContaining("7");

        server.reset();
        server.expect(requestTo("https://groq.test/openai/v1/chat/completions")).andRespond(withServerError());
        assertThatThrownBy(() -> client.complete(new LlmRequest("s", "u", null, true, 10)))
                .isInstanceOf(LlmUnavailableException.class);

        server.reset();
        server.expect(requestTo("https://groq.test/openai/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"model decommissioned\"}"));
        assertThatThrownBy(() -> client.complete(new LlmRequest("s", "u", null, true, 10)))
                .isInstanceOf(LlmRejectedException.class).hasMessageContaining("model decommissioned");
    }

    @Test
    void withoutAnApiKeyTheApplicationRefusesToStart() {
        AiProperties.Groq noKey = new AiProperties.Groq(SETTINGS.baseUrl(), " ", "t", "v", Duration.ofSeconds(1));

        assertThatThrownBy(() -> new GroqLlmClient(RestClient.create(), noKey, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GROQ_API_KEY");
    }
}
