package com.claimsai.ai.config;

import com.claimsai.ai.app.AiProperties;
import com.claimsai.ai.domain.LlmClient;
import com.claimsai.ai.infra.GroqLlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class AiConfig {

    /** Groq with explicit timeouts: a hung call must fail and be retried, not hold a job's lease forever. */
    @Bean
    @ConditionalOnProperty(name = "app.ai.provider", havingValue = "groq")
    public LlmClient groqLlmClient(AiProperties properties, RestClient.Builder builder, ObjectMapper objectMapper) {
        AiProperties.Groq groq = properties.groq();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(groq.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(groq.timeout());
        RestClient http = builder.baseUrl(groq.baseUrl().toString()).requestFactory(factory).build();
        return new GroqLlmClient(http, groq, objectMapper);
    }
}
