package com.claimsai.support;

import com.claimsai.identity.api.AuthDtos.LoginRequest;
import com.claimsai.identity.api.AuthDtos.TokenResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for integration tests: the real application over HTTP on a random port, against PostgreSQL in
 * Testcontainers. All subclasses share one Spring context (and one container) because the configuration
 * is identical.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, RoleProbeController.class})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Password1!";

    @Autowired
    protected TestRestTemplate http;

    protected TokenResponse login(String username) {
        ResponseEntity<TokenResponse> response = http.postForEntity("/api/v1/auth/login",
                new LoginRequest(username, PASSWORD), TokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected HttpEntity<Void> bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }
}
