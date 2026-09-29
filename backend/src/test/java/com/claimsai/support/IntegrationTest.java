package com.claimsai.support;

import com.claimsai.claim.api.ClaimDtos.FnolRequest;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.LossType;
import com.claimsai.identity.api.AuthDtos.LoginRequest;
import com.claimsai.identity.api.AuthDtos.TokenResponse;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
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
@Import({TestcontainersConfiguration.class, RoleProbeController.class, TestBackgroundWork.class})
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Password1!";
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** Runs code in a transaction, as a service would (for MANDATORY services like JobService). */
    protected <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    /** Background work (jobs, outbox) runs on its own: wait for its effect, never sleep a fixed time. */
    protected static org.awaitility.core.ConditionFactory eventually() {
        return Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100));
    }

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

    /** A claimant files a collision claim through the portal and intake finishes; returns its id. */
    protected Long portalClaim(String claimant, String policyNumber) {
        ResponseEntity<PortalClaimResponse> response = fileClaim(claimant, "/api/v1/portal/claims",
                fnol(policyNumber, LossType.VEHICLE_COLLISION, "3800", false), UUID.randomUUID().toString(),
                PortalClaimResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return awaitIntake(response.getBody().id());
    }

    /** Waits until the intake jobs have moved the claim past SUBMITTED / ASSESSING. */
    protected Long awaitIntake(Long claimId) {
        eventually().until(() -> {
            ClaimStatus status = asSupervisor(claimId).status();
            return status != ClaimStatus.SUBMITTED && status != ClaimStatus.ASSESSING;
        });
        return claimId;
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

    // ---------- documents ----------

    private static final java.net.http.HttpClient BROWSER = java.net.http.HttpClient.newHttpClient();

    /** What the browser does: PUT the bytes to the presigned URL with the given headers. */
    protected static int putToStorage(com.claimsai.document.api.DocumentDtos.UploadInstructions upload, byte[] bytes)
            throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(upload.uploadUrl()))
                .method(upload.method(), java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes));
        upload.headers().forEach(request::header);
        return BROWSER.send(request.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** Start, PUT and complete an upload through the portal (claimant) or staff API; returns the document id. */
    protected Long uploadDocument(String username, Long claimId, byte[] bytes, String contentType,
                                  com.claimsai.document.domain.Document.Category category) throws Exception {
        boolean portal = username.startsWith("claimant");
        String base = portal ? "/api/v1/portal" : "/api/v1";
        var request = new com.claimsai.document.api.DocumentDtos.UploadUrlRequest("file", contentType, bytes.length,
                category);
        var started = post(username, base + "/claims/" + claimId + "/documents", request, Map.class).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) started.get("document");
        @SuppressWarnings("unchecked")
        Map<String, Object> upload = (Map<String, Object>) started.get("upload");
        @SuppressWarnings("unchecked")
        var instructions = new com.claimsai.document.api.DocumentDtos.UploadInstructions((String) upload.get("uploadUrl"),
                (String) upload.get("method"), (Map<String, String>) upload.get("headers"), null);
        assertThat(putToStorage(instructions, bytes)).isEqualTo(200);
        Long documentId = ((Number) document.get("id")).longValue();
        assertThat(post(username, base + "/documents/" + documentId + "/complete", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return documentId;
    }

    /**
     * A fresh auto policy (collision + comprehensive, in force since January) for this test only. Fraud
     * rules look at the policy's history, so tests that check scores must not share a policy with others.
     */
    protected String newAutoPolicy(String holderUsername) {
        String number = "POL-T-" + (100000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900000));
        jdbc.sql("INSERT INTO policy (policy_number, product, holder_user_id, holder_name, status, effective_from, "
                        + "effective_to, deductible) SELECT :number, 'AUTO', id, display_name, 'ACTIVE', "
                        + "DATE '2026-01-01', DATE '2027-01-01', 500 FROM app_user WHERE username = :holder")
                .param("number", number).param("holder", holderUsername).update();
        jdbc.sql("INSERT INTO policy_coverage (policy_number, coverage_type, limit_amount) "
                        + "VALUES (:number, 'COLLISION', 25000), (:number, 'COMPREHENSIVE', 15000)")
                .param("number", number).update();
        return number;
    }

    /** "Time passes": due retries of this claim's jobs of a type become due now. */
    protected void makeJobsDue(Long claimId, String type) {
        jdbc.sql("UPDATE job SET due_at = :now WHERE claim_id = :claimId AND type = :type AND status = 'PENDING'")
                .param("now", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)))
                .param("claimId", claimId).param("type", type).update();
    }

    protected String etag(String username, String path) {
        return get(username, path, String.class).getHeaders().getETag();
    }
}
