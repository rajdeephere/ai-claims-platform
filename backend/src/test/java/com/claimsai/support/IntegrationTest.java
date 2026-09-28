package com.claimsai.support;

import com.claimsai.claim.api.ClaimDtos.FnolRequest;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.domain.LossType;
import com.claimsai.identity.api.AuthDtos.LoginRequest;
import com.claimsai.identity.api.AuthDtos.TokenResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for integration tests: the real application over HTTP on a random port, against PostgreSQL in
 * Testcontainers. All subclasses share one Spring context (and one container) because the configuration
 * is identical. Tests share the database too, so they never assume which adjuster gets a claim: they ask.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, RoleProbeController.class})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Password1!";
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    protected TestRestTemplate http;

    protected TokenResponse login(String username) {
        ResponseEntity<TokenResponse> response = http.postForEntity("/api/v1/auth/login",
                new LoginRequest(username, PASSWORD), TokenResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** A cached access token (valid 15 minutes, far longer than the test run). */
    protected String token(String username) {
        return TOKENS.computeIfAbsent(username, u -> login(u).accessToken());
    }

    protected HttpEntity<Void> bearer(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(headers);
    }

    protected HttpHeaders headers(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(username));
        return headers;
    }

    protected <T> ResponseEntity<T> get(String username, String path, Class<T> type) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(username)), type);
    }

    protected <T> ResponseEntity<T> post(String username, String path, Object body, Class<T> type) {
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(username)), type);
    }

    protected <T> ResponseEntity<T> postIfMatch(String username, String path, String etag, Object body, Class<T> type) {
        HttpHeaders headers = headers(username);
        if (etag != null) {
            headers.setIfMatch(etag);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }

    // ---------- claims ----------

    protected static FnolRequest fnol(String policyNumber, LossType lossType, String estimate, boolean injuries) {
        return new FnolRequest(policyNumber, LocalDate.now().minusDays(3), lossType, "MG Road, Bengaluru",
                "Rear-ended at a traffic signal, bumper damaged", injuries,
                estimate == null ? null : new BigDecimal(estimate), null, "+91 98450 12345");
    }

    protected <T> ResponseEntity<T> fileClaim(String username, String path, FnolRequest request, String key,
                                              Class<T> type) {
        HttpHeaders headers = headers(username);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(request, headers), type);
    }

    /** claimant1 files a normal collision claim through the portal; returns its id. */
    protected Long portalClaim(String claimant, String policyNumber) {
        ResponseEntity<PortalClaimResponse> response = fileClaim(claimant, "/api/v1/portal/claims",
                fnol(policyNumber, LossType.VEHICLE_COLLISION, "3800", false), UUID.randomUUID().toString(),
                PortalClaimResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    /** The staff view, read by the supervisor (who sees every claim). */
    protected StaffClaimResponse asSupervisor(Long claimId) {
        return get("supervisor1", "/api/v1/claims/" + claimId, StaffClaimResponse.class).getBody();
    }

    protected String assignedAdjuster(Long claimId) {
        return asSupervisor(claimId).assignedAdjuster().username();
    }

    protected String otherAdjuster(String adjuster) {
        return adjuster.equals("adjuster1") ? "adjuster2" : "adjuster1";
    }

    protected String etag(String username, String path) {
        return get(username, path, String.class).getHeaders().getETag();
    }
}
