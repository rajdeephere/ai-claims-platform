package com.claimsai.financials;

import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.ReasonRequest;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.claim.api.ClaimDtos.WithdrawRequest;
import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.common.error.ApiError;
import com.claimsai.financials.api.FinancialsDtos.ApprovalResponse;
import com.claimsai.financials.api.FinancialsDtos.CreateExposureRequest;
import com.claimsai.financials.api.FinancialsDtos.DecisionRequest;
import com.claimsai.financials.api.FinancialsDtos.ExposureResponse;
import com.claimsai.financials.api.FinancialsDtos.PaymentRequest;
import com.claimsai.financials.api.FinancialsDtos.PaymentResponse;
import com.claimsai.financials.api.FinancialsDtos.RecoveryRequest;
import com.claimsai.financials.api.FinancialsDtos.ReserveRequest;
import com.claimsai.financials.api.FinancialsDtos.ReserveResponse;
import com.claimsai.financials.domain.ApprovalRequest;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.financials.domain.Payment;
import com.claimsai.financials.domain.Recovery;
import com.claimsai.notification.api.PortalNotificationController.NotificationView;
import com.claimsai.common.web.PageResponse;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialsIT extends IntegrationTest {

    // ---------- helpers ----------

    private record OpenClaim(Long id, String adjuster) {
    }

    private OpenClaim openClaim() {
        Long id = portalClaim("claimant1", newAutoPolicy("claimant1"));
        return new OpenClaim(id, assignedAdjuster(id));
    }

    private ReserveResponse createExposure(String user, Long claimId, String reserve) {
        ResponseEntity<ReserveResponse> response = post(user, "/api/v1/claims/" + claimId + "/exposures",
                new CreateExposureRequest(Exposure.Type.VEHICLE_DAMAGE, "Asha Verma (insured)",
                        reserve == null ? null : new BigDecimal(reserve), "initial estimate"), ReserveResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private <T> ResponseEntity<T> pay(String user, Long exposureId, String amount, String payee, String key, Class<T> type) {
        HttpHeaders headers = headers(user);
        headers.set("Idempotency-Key", key);
        return http.exchange("/api/v1/exposures/" + exposureId + "/payments", HttpMethod.POST,
                new HttpEntity<>(new PaymentRequest(new BigDecimal(amount), payee, "repair"), headers), type);
    }

    private PaymentResponse payOk(String user, Long exposureId, String amount, String payee) {
        ResponseEntity<PaymentResponse> response = pay(user, exposureId, amount, payee, UUID.randomUUID().toString(),
                PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private PaymentResponse payment(Long claimId, Long paymentId) {
        return List.of(get("supervisor1", "/api/v1/claims/" + claimId + "/payments", PaymentResponse[].class).getBody())
                .stream().filter(p -> p.id().equals(paymentId)).findFirst().orElseThrow();
    }

    private ExposureResponse exposure(Long claimId, Long exposureId) {
        return List.of(get("supervisor1", "/api/v1/claims/" + claimId + "/exposures", ExposureResponse[].class).getBody())
                .stream().filter(e -> e.id().equals(exposureId)).findFirst().orElseThrow();
    }

    private void awaitPayment(Long claimId, Long paymentId, Payment.Status status) {
        eventually().until(() -> payment(claimId, paymentId).status() == status);
    }

    private <T> ResponseEntity<T> approve(String user, Long approvalId, Class<T> type) {
        return post(user, "/api/v1/approvals/" + approvalId + "/approve", new DecisionRequest("ok"), type);
    }

    private <T> ResponseEntity<T> closeExposure(String user, Long claimId, Long exposureId, Class<T> type) {
        return postIfMatch(user, "/api/v1/exposures/" + exposureId + "/close",
                "\"" + exposure(claimId, exposureId).version() + "\"", new ReasonRequest("repaired"), type);
    }

    private static ApiError error(ResponseEntity<ApiError> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        return response.getBody();
    }

    // ---------- tests ----------

    @Test
    void aPaymentWithinTheAdjustersAuthorityIsIssuedAndTheClaimClosesAsPaid() {
        OpenClaim claim = openClaim();
        ReserveResponse created = createExposure(claim.adjuster(), claim.id(), "4000");
        assertThat(created.pendingApproval()).isNull();
        Long exposureId = created.exposure().id();
        assertThat(created.exposure().reserve()).isEqualByComparingTo("4000");

        PaymentResponse requested = payOk(claim.adjuster(), exposureId, "3800", "City Motors Pvt Ltd");
        assertThat(requested.status()).isEqualTo(Payment.Status.APPROVED);
        awaitPayment(claim.id(), requested.id(), Payment.Status.ISSUED);
        assertThat(payment(claim.id(), requested.id()).externalReference()).startsWith("PAY-");

        ExposureResponse afterPayment = exposure(claim.id(), exposureId);
        assertThat(afterPayment.paid()).isEqualByComparingTo("3800");
        assertThat(afterPayment.available()).isEqualByComparingTo("200");

        // the claim can't close while an exposure is open
        ResponseEntity<ApiError> tooEarly = postIfMatch(claim.adjuster(), "/api/v1/claims/" + claim.id() + "/close",
                etag(claim.adjuster(), "/api/v1/claims/" + claim.id()), new ReasonRequest("done"), ApiError.class);
        assertThat(error(tooEarly, HttpStatus.UNPROCESSABLE_ENTITY).code()).isEqualTo("OPEN_EXPOSURES");

        ExposureResponse closed = closeExposure(claim.adjuster(), claim.id(), exposureId, ExposureResponse.class).getBody();
        assertThat(closed.reserve()).isEqualByComparingTo("3800");   // 200 released
        StaffClaimResponse closedClaim = postIfMatch(claim.adjuster(), "/api/v1/claims/" + claim.id() + "/close",
                etag(claim.adjuster(), "/api/v1/claims/" + claim.id()), new ReasonRequest("paid"),
                StaffClaimResponse.class).getBody();
        assertThat(closedClaim.closeOutcome()).isEqualTo(CloseOutcome.PAID);

        PortalClaimResponse portal = get("claimant1", "/api/v1/portal/claims/" + claim.id(), PortalClaimResponse.class)
                .getBody();
        assertThat(portal.status()).isEqualTo(ClaimantStatus.PAID);
        assertThat(portal.amountPaid()).isEqualByComparingTo("3800");
        NotificationView paid = eventually().until(() -> http.exchange("/api/v1/portal/notifications?size=100",
                        HttpMethod.GET, new HttpEntity<>(headers("claimant1")),
                        new ParameterizedTypeReference<PageResponse<NotificationView>>() { }).getBody().content().stream()
                .filter(n -> claim.id().equals(n.claimId()) && n.subject().startsWith("Payment issued")).findFirst()
                .orElse(null), java.util.Objects::nonNull);
        assertThat(paid.body()).isEqualTo("A payment of Rs 3800.00 to City Motors Pvt Ltd has been issued.");   // BUG-012
    }

    @Test
    void aboveAuthorityBothTheReserveAndThePaymentNeedASupervisor() {
        OpenClaim claim = openClaim();
        ReserveResponse created = createExposure(claim.adjuster(), claim.id(), "15000");   // limit 5,000
        Long exposureId = created.exposure().id();
        assertThat(created.exposure().reserve()).isEqualByComparingTo("0");
        assertThat(created.pendingApproval().kind()).isEqualTo(ApprovalRequest.Kind.RESERVE_CHANGE);

        ApiError nothingReserved = error(pay(claim.adjuster(), exposureId, "1000", "City Motors",
                UUID.randomUUID().toString(), ApiError.class), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(nothingReserved.code()).isEqualTo("INSUFFICIENT_RESERVE");
        assertThat(approve(claim.adjuster(), created.pendingApproval().id(), ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(approve("supervisor1", created.pendingApproval().id(), ApprovalResponse.class).getBody().status())
                .isEqualTo(ApprovalRequest.Status.APPROVED);
        assertThat(exposure(claim.id(), exposureId).reserve()).isEqualByComparingTo("15000");

        PaymentResponse big = payOk(claim.adjuster(), exposureId, "12000", "City Motors Pvt Ltd");
        assertThat(big.status()).isEqualTo(Payment.Status.PENDING_APPROVAL);
        assertThat(exposure(claim.id(), exposureId).available()).isEqualByComparingTo("3000");   // committed

        ApiError pending = error(postIfMatch(claim.adjuster(), "/api/v1/claims/" + claim.id() + "/close",
                etag(claim.adjuster(), "/api/v1/claims/" + claim.id()), new ReasonRequest("x"), ApiError.class),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(pending.code()).isEqualTo("OPEN_EXPOSURES");

        approve("supervisor1", big.approvalRequestId(), ApprovalResponse.class);
        awaitPayment(claim.id(), big.id(), Payment.Status.ISSUED);
        assertThat(payment(claim.id(), big.id()).approvedBy()).isNotEqualTo(big.requestedBy());

        List<String> actions = List.of(get("supervisor1", "/api/v1/claims/" + claim.id() + "/timeline",
                TimelineEntryResponse[].class).getBody()).stream().map(TimelineEntryResponse::action).toList();
        assertThat(actions).containsSubsequence("EXPOSURE_CREATED", "APPROVAL_REQUESTED", "APPROVAL_APPROVED",
                "APPROVAL_REQUESTED", "APPROVAL_APPROVED", "PAYMENT_ISSUED");
    }

    @Test
    void nobodyApprovesTheirOwnRequestOrBeyondTheirOwnLimit() {
        OpenClaim claim = openClaim();
        Long exposureId = createExposure(claim.adjuster(), claim.id(), "1000").exposure().id();

        // the supervisor raises a reserve above their own 50,000 limit: it needs someone else
        ReserveResponse raised = put("supervisor1", "/api/v1/exposures/" + exposureId + "/reserve",
                "\"" + exposure(claim.id(), exposureId).version() + "\"", new ReserveRequest(new BigDecimal("70000"),
                        "total loss"));
        ApiError self = error(approve("supervisor1", raised.pendingApproval().id(), ApiError.class),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(self.code()).isEqualTo("SELF_APPROVAL");

        // an adjuster's request above the supervisor's limit: the supervisor may not approve it either
        OpenClaim other = openClaim();
        ApprovalResponse pending = createExposure(other.adjuster(), other.id(), "60000").pendingApproval();
        ApiError tooMuch = error(approve("supervisor1", pending.id(), ApiError.class), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooMuch.code()).isEqualTo("AUTHORITY_EXCEEDED");
    }

    @Test
    void aRejectedPaymentGivesItsReserveBack() {
        OpenClaim claim = openClaim();
        ReserveResponse created = createExposure(claim.adjuster(), claim.id(), "15000");
        Long exposureId = created.exposure().id();
        approve("supervisor1", created.pendingApproval().id(), ApprovalResponse.class);
        PaymentResponse big = payOk(claim.adjuster(), exposureId, "12000", "City Motors");

        assertThat(error(post("supervisor1", "/api/v1/approvals/" + big.approvalRequestId() + "/reject",
                new ReasonRequest(" "), ApiError.class), HttpStatus.BAD_REQUEST)).isNotNull();
        ApprovalResponse rejected = post("supervisor1", "/api/v1/approvals/" + big.approvalRequestId() + "/reject",
                new ReasonRequest("invoice doesn't match the survey"), ApprovalResponse.class).getBody();

        assertThat(rejected.status()).isEqualTo(ApprovalRequest.Status.REJECTED);
        assertThat(payment(claim.id(), big.id()).status()).isEqualTo(Payment.Status.REJECTED);
        assertThat(exposure(claim.id(), exposureId).available()).isEqualByComparingTo("15000");
        assertThat(error(approve("supervisor1", big.approvalRequestId(), ApiError.class), HttpStatus.CONFLICT).code())
                .isEqualTo("ALREADY_DECIDED");
    }

    @Test
    void aRetriedPaymentRequestReturnsTheFirstPayment() {
        OpenClaim claim = openClaim();
        Long exposureId = createExposure(claim.adjuster(), claim.id(), "4000").exposure().id();
        String key = UUID.randomUUID().toString();

        ResponseEntity<PaymentResponse> first = pay(claim.adjuster(), exposureId, "1000", "City Motors", key,
                PaymentResponse.class);
        ResponseEntity<PaymentResponse> retry = pay(claim.adjuster(), exposureId, "1000.00", "City Motors", key,
                PaymentResponse.class);
        ResponseEntity<ApiError> different = pay(claim.adjuster(), exposureId, "2000", "City Motors", key, ApiError.class);

        assertThat(retry.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(error(different, HttpStatus.UNPROCESSABLE_ENTITY).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(List.of(get("supervisor1", "/api/v1/claims/" + claim.id() + "/payments", PaymentResponse[].class)
                .getBody())).hasSize(1);
    }

    @Test
    void twoConcurrentPaymentsCanNeverOverspendTheReserve() throws Exception {
        OpenClaim claim = openClaim();
        Long exposureId = createExposure(claim.adjuster(), claim.id(), "4000").exposure().id();
        token(claim.adjuster());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<String>> payment = () -> pay(claim.adjuster(), exposureId, "3000", "City Motors",
                    UUID.randomUUID().toString(), String.class);
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : pool.invokeAll(List.of(payment, payment))) {
                statuses.add(HttpStatus.valueOf(f.get().getStatusCode().value()));
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void noMoneyGoesOutWhileTheClaimIsUnderSiuReview() {
        OpenClaim claim = openClaim();
        ReserveResponse created = createExposure(claim.adjuster(), claim.id(), "15000");
        Long exposureId = created.exposure().id();
        approve("supervisor1", created.pendingApproval().id(), ApprovalResponse.class);
        PaymentResponse big = payOk(claim.adjuster(), exposureId, "12000", "City Motors");
        jdbc.sql("UPDATE claim SET status = 'SIU_REVIEW' WHERE id = ?").param(claim.id()).update();

        ApiError request = error(pay(claim.adjuster(), exposureId, "100", "City Motors", UUID.randomUUID().toString(),
                ApiError.class), HttpStatus.UNPROCESSABLE_ENTITY);
        ApiError approval = error(approve("supervisor1", big.approvalRequestId(), ApiError.class),
                HttpStatus.UNPROCESSABLE_ENTITY);

        assertThat(request.code()).isEqualTo("SIU_HOLD");
        assertThat(approval.code()).isEqualTo("SIU_HOLD");
        assertThat(payment(claim.id(), big.id()).status()).isEqualTo(Payment.Status.PENDING_APPROVAL);
    }

    @Test
    void aRefusedPaymentFailsAndGivesTheReserveBack() {
        OpenClaim claim = openClaim();
        Long exposureId = createExposure(claim.adjuster(), claim.id(), "4000").exposure().id();

        PaymentResponse refused = payOk(claim.adjuster(), exposureId, "3000", "REFUSE Motors");
        awaitPayment(claim.id(), refused.id(), Payment.Status.FAILED);

        assertThat(payment(claim.id(), refused.id()).failureReason()).isEqualTo("payee account closed");
        assertThat(exposure(claim.id(), exposureId).available()).isEqualByComparingTo("4000");
    }

    @Test
    void aLostResponseFromThePaymentRailNeverPaysTwice() {
        OpenClaim claim = openClaim();
        Long exposureId = createExposure(claim.adjuster(), claim.id(), "4000").exposure().id();

        PaymentResponse payment = payOk(claim.adjuster(), exposureId, "2500", "LOST-RESPONSE Garage");
        // the rail paid, but its answer was lost: the job retries (after backoff) with the same key
        eventually().until(() -> {
            makeJobsDue(claim.id(), "ISSUE_PAYMENT");
            return payment(claim.id(), payment.id()).status() == Payment.Status.ISSUED;
        });

        String key = jdbc.sql("SELECT idempotency_key FROM payment WHERE id = ?").param(payment.id())
                .query(String.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM payment_rail_stub WHERE idempotency_key = ?").param(key)
                .query(Long.class).single()).as("the bank's side has ONE payment").isEqualTo(1);
        assertThat(payment(claim.id(), payment.id()).externalReference()).isEqualTo(
                jdbc.sql("SELECT reference FROM payment_rail_stub WHERE idempotency_key = ?").param(key)
                        .query(String.class).single());
        assertThat(exposure(claim.id(), exposureId).paid()).isEqualByComparingTo("2500");
    }

    @Test
    void aDenialIsProposedByTheAdjusterAndDecidedByASupervisor() {
        OpenClaim claim = openClaim();
        createExposure(claim.adjuster(), claim.id(), "4000");

        ResponseEntity<ApprovalResponse> proposed = post(claim.adjuster(), "/api/v1/claims/" + claim.id()
                + "/denial-requests", new ReasonRequest("Loss excluded: racing on a private track"), ApprovalResponse.class);
        assertThat(proposed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(error(post(claim.adjuster(), "/api/v1/claims/" + claim.id() + "/denial-requests",
                new ReasonRequest("again"), ApiError.class), HttpStatus.CONFLICT).code()).isEqualTo("APPROVAL_PENDING");
        assertThat(approve(claim.adjuster(), proposed.getBody().id(), ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        approve("supervisor1", proposed.getBody().id(), ApprovalResponse.class);

        StaffClaimResponse denied = asSupervisor(claim.id());
        assertThat(denied.status()).isEqualTo(ClaimStatus.CLOSED);
        assertThat(denied.closeOutcome()).isEqualTo(CloseOutcome.DENIED);
        assertThat(List.of(get("supervisor1", "/api/v1/claims/" + claim.id() + "/exposures", ExposureResponse[].class)
                .getBody())).allSatisfy(e -> assertThat(e.status()).isEqualTo(Exposure.Status.CLOSED));
        assertThat(get("claimant1", "/api/v1/portal/claims/" + claim.id(), PortalClaimResponse.class).getBody().status())
                .isEqualTo(ClaimantStatus.DENIED);
        assertThat(List.of(get("supervisor1", "/api/v1/claims/" + claim.id() + "/timeline", TimelineEntryResponse[].class)
                .getBody())).filteredOn(e -> e.action().equals("STATUS_CHANGED")).last()
                .satisfies(e -> assertThat(e.actor()).isEqualTo("supervisor1"));
    }

    @Test
    void recoveriesNeedAPaymentAndWithdrawalIsImpossibleOnceMoneyWentOut() {
        OpenClaim claim = openClaim();
        Long exposureId = createExposure(claim.adjuster(), claim.id(), "4000").exposure().id();
        RecoveryRequest recovery = new RecoveryRequest(new BigDecimal("3800"), Recovery.Source.THIRD_PARTY_INSURER,
                "SUB-778", LocalDate.now());

        assertThat(error(post(claim.adjuster(), "/api/v1/claims/" + claim.id() + "/recoveries", recovery, ApiError.class),
                HttpStatus.UNPROCESSABLE_ENTITY).code()).isEqualTo("NO_PAYMENT_TO_RECOVER");

        PaymentResponse paid = payOk(claim.adjuster(), exposureId, "3800", "City Motors");
        awaitPayment(claim.id(), paid.id(), Payment.Status.ISSUED);
        assertThat(post(claim.adjuster(), "/api/v1/claims/" + claim.id() + "/recoveries", recovery, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ApiError withdraw = error(postIfMatch("claimant1", "/api/v1/portal/claims/" + claim.id() + "/withdraw",
                etag("claimant1", "/api/v1/portal/claims/" + claim.id()), new WithdrawRequest("never mind"),
                ApiError.class), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(withdraw.code()).isEqualTo("PAYMENT_ALREADY_ISSUED");
        // ... and the claimant is never offered the button in the first place (BUG-014)
        assertThat(get("claimant1", "/api/v1/portal/claims/" + claim.id(), PortalClaimResponse.class).getBody()
                .allowedActions()).doesNotContain(com.claimsai.claim.domain.ClaimAction.WITHDRAW);
    }

    @Test
    void theReserveCantBeLoweredBelowWhatIsPaidOrBeingPaid() {
        OpenClaim claim = openClaim();
        // being paid: 12,000 waits for approval (above the adjuster's limit), so it never issues on its own
        Long exposureId = createExposure("supervisor1", claim.id(), "15000").exposure().id();
        assertThat(payOk(claim.adjuster(), exposureId, "12000", "City Motors").status())
                .isEqualTo(Payment.Status.PENDING_APPROVAL);

        assertThat(error(lowerReserve(claim, exposureId, "10000", currentETag(claim, exposureId)),
                HttpStatus.UNPROCESSABLE_ENTITY).code()).isEqualTo("RESERVE_BELOW_COMMITTED");
    }

    @Test
    void paymentsIssuedInTheBackgroundMakeAnOlderViewOfTheExposureStale() {
        OpenClaim claim = openClaim();
        Long exposureId = createExposure(claim.adjuster(), claim.id(), "4000").exposure().id();
        String seenBeforeIssuing = currentETag(claim, exposureId);
        PaymentResponse paid = payOk(claim.adjuster(), exposureId, "3000", "City Motors");
        awaitPayment(claim.id(), paid.id(), Payment.Status.ISSUED);

        // the adjuster decided on numbers that have changed since: refused, not applied (BUG-013)
        assertThat(lowerReserve(claim, exposureId, "3500", seenBeforeIssuing).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(error(lowerReserve(claim, exposureId, "2000", currentETag(claim, exposureId)),
                HttpStatus.UNPROCESSABLE_ENTITY).code()).isEqualTo("RESERVE_BELOW_COMMITTED");
    }

    private String currentETag(OpenClaim claim, Long exposureId) {
        return "\"" + exposure(claim.id(), exposureId).version() + "\"";
    }

    private ResponseEntity<ApiError> lowerReserve(OpenClaim claim, Long exposureId, String amount, String etag) {
        return http.exchange("/api/v1/exposures/" + exposureId + "/reserve", HttpMethod.PUT,
                new HttpEntity<>(new ReserveRequest(new BigDecimal(amount), "estimate revised"),
                        ifMatch(claim.adjuster(), etag)), ApiError.class);
    }

    private HttpHeaders ifMatch(String user, String etag) {
        HttpHeaders headers = headers(user);
        headers.setIfMatch(etag);
        return headers;
    }

    private ReserveResponse put(String user, String path, String etag, Object body) {
        ResponseEntity<ReserveResponse> response = http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body,
                ifMatch(user, etag)), ReserveResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }
}
