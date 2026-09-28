package com.claimsai.common;

import com.claimsai.common.error.ApiError;
import com.claimsai.support.IntegrationTest;
import com.claimsai.support.OpenApiContract;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-cutting behaviour: health, error format, correlation ID, published API contract. */
class PlatformApiIT extends IntegrationTest {

    @Test
    void healthIsPublicAndUp() {
        ResponseEntity<String> health = http.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void unknownRouteIs404NotA500() {
        ResponseEntity<ApiError> response = http.exchange("/api/v1/does-not-exist", HttpMethod.GET,
                bearer(login("adjuster1").accessToken()), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void correlationIdIsGeneratedWhenAbsentAndUnsafeValuesAreReplaced() {
        ResponseEntity<String> generated = http.getForEntity("/actuator/health", String.class);
        assertThat(generated.getHeaders().getFirst("X-Correlation-Id")).hasSize(36);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "bad value with spaces");
        ResponseEntity<String> replaced = http.exchange("/actuator/health", HttpMethod.GET, new HttpEntity<>(headers),
                String.class);
        assertThat(replaced.getHeaders().getFirst("X-Correlation-Id")).isNotEqualTo("bad value with spaces").hasSize(36);
    }

    @Test
    void publishedOpenApiContractIsUpToDate() throws Exception {
        String live = http.getForObject("/v3/api-docs/v1", String.class);
        OpenApiContract.assertMatchesCommittedContract(live, "api");
    }
}
