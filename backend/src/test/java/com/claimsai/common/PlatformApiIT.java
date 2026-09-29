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

    /**
     * The Angular client's method names come from operationIds. springdoc derives them from Java method names
     * and adds "_1", "_2" on collisions, numbered by scan order: adding one controller renamed existing
     * operations (BUG-005). Every operation must carry an explicit, unique id.
     */
    @Test
    void everyOperationHasAnExplicitUniqueOperationId() throws Exception {
        var paths = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(http.getForObject("/v3/api-docs/v1", String.class)).path("paths");
        java.util.List<String> ids = new java.util.ArrayList<>();
        paths.forEach(path -> path.forEach(operation -> ids.add(operation.path("operationId").asText())));

        assertThat(ids).isNotEmpty().doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).as("operationId").matches("[a-z][A-Za-z]+").doesNotMatch(".*_\\d+"));
    }

    @Test
    void publishedOpenApiContractIsUpToDate() throws Exception {
        String live = http.getForObject("/v3/api-docs/v1", String.class);
        OpenApiContract.assertMatchesCommittedContract(live, "api");
    }
}
