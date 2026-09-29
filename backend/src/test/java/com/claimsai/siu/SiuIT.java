package com.claimsai.siu;

import com.claimsai.activity.api.ActivityDtos.ActivityResponse;
import com.claimsai.activity.domain.Activity;
import com.claimsai.activity.domain.ActivityType;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.ReasonRequest;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.common.error.ApiError;
import com.claimsai.common.web.PageResponse;
import com.claimsai.financials.api.FinancialsDtos.ApprovalResponse;
import com.claimsai.financials.api.FinancialsDtos.CreateExposureRequest;
import com.claimsai.financials.api.FinancialsDtos.DecisionRequest;
import com.claimsai.financials.api.FinancialsDtos.PaymentRequest;
import com.claimsai.financials.api.FinancialsDtos.ReserveResponse;
import com.claimsai.financials.domain.ApprovalRequest;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.notification.api.PortalNotificationController.NotificationView;
import com.claimsai.siu.api.SiuDtos.CaseFileResponse;
import com.claimsai.siu.api.SiuDtos.OutcomeRequest;
import com.claimsai.siu.api.SiuDtos.SiuCaseResponse;
import com.claimsai.siu.domain.SiuCase;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SiuIT extends IntegrationTest {

    private Long openClaim() {
        return portalClaim("claimant1", newAutoPolicy("claimant1"));
    }

    private String claimPath(Long claimId) {
        return "/api/v1/claims/" + claimId;
    }

    private ResponseEntity<StaffClaimResponse> refer(String user, Long claimId, String reason) {
        return postIfMatch(user, claimPath(claimId) + "/refer-siu", etag(user, claimPath(claimId)),
                new ReasonRequest(reason), StaffClaimResponse.class);
    }

    private SiuCaseResponse caseOf(Long claimId) {
        SiuCaseResponse[] cases = get("supervisor1", claimPath(claimId) + "/siu-cases", SiuCaseResponse[].class).getBody();
        return cases[cases.length - 1];
    }

    private List<ActivityResponse> activities(Long claimId) {
        return List.of(get("supervisor1", claimPath(claimId) + "/activities", ActivityResponse[].class).getBody());
    }

    private ActivityResponse activity(Long claimId, ActivityType type) {
        return activities(claimId).stream().filter(a -> a.type() == type).reduce((a, b) -> b).orElse(null);
    }

    private <T> ResponseEntity<T> outcome(String user, Long caseId, SiuCase.Outcome outcome, String findings,
                                          Class<T> type) {
        return post(user, "/api/v1/siu/cases/" + caseId + "/outcome", new OutcomeRequest(outcome, findings), type);
    }

    private ResponseEntity<String> pay(String user, Long exposureId) {
        HttpHeaders headers = headers(user);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return http.exchange("/api/v1/exposures/" + exposureId + "/payments", HttpMethod.POST,
                new HttpEntity<>(new PaymentRequest(new BigDecimal("500"), "City Motors", "repair"), headers), String.class);
    }

    @Test
    void aReferralOpensACaseHoldsTheMoneyAndOnlySiuDecidesIt() {
        Long claimId = openClaim();
        String adjuster = assignedAdjuster(claimId);
        Long exposureId = post(adjuster, claimPath(claimId) + "/exposures", new CreateExposureRequest(
                Exposure.Type.VEHICLE_DAMAGE, "Asha Verma", new BigDecimal("4000"), null), ReserveResponse.class)
                .getBody().exposure().id();
        assertThat(get("siu1", claimPath(claimId), String.class).getStatusCode())
                .as("SIU sees referred claims only").isEqualTo(HttpStatus.NOT_FOUND);

        StaffClaimResponse referred = refer(adjuster, claimId, "Garage invoice looks copied from another claim").getBody();

        assertThat(referred.status()).isEqualTo(ClaimStatus.SIU_REVIEW);
        SiuCaseResponse siuCase = caseOf(claimId);
        assertThat(siuCase.source()).isEqualTo(SiuCase.Source.MANUAL);
        assertThat(siuCase.referredBy().username()).isEqualTo(adjuster);
        assertThat(siuCase.status()).isEqualTo(SiuCase.Status.OPEN);

        // the investigator's view: the claim and its case file, but nothing they may change
        StaffClaimResponse asSiu = get("siu1", claimPath(claimId), StaffClaimResponse.class).getBody();
        assertThat(asSiu.allowedActions()).containsExactly(ClaimAction.ADD_NOTE);
        CaseFileResponse file = get("siu1", "/api/v1/siu/cases/" + siuCase.id(), CaseFileResponse.class).getBody();
        assertThat(file.claim().status()).isEqualTo(ClaimStatus.SIU_REVIEW);
        assertThat(file.otherClaimsOnPolicy()).isEmpty();
        assertThat(http.exchange("/api/v1/siu/cases?size=100", HttpMethod.GET, new HttpEntity<>(headers("siu1")),
                        new ParameterizedTypeReference<PageResponse<SiuCaseResponse>>() { }).getBody().content())
                .extracting(SiuCaseResponse::id).contains(siuCase.id());

        // the SIU queue has the work; the claimant sees nothing unusual
        eventually().until(() -> activity(claimId, ActivityType.SIU_INVESTIGATION) != null);
        ActivityResponse investigation = activity(claimId, ActivityType.SIU_INVESTIGATION);
        assertThat(investigation.candidateRole()).hasToString("SIU");
        assertThat(investigation.linkedId()).isEqualTo(siuCase.id());
        assertThat(get("claimant1", "/api/v1/portal/claims/" + claimId, PortalClaimResponse.class).getBody().status())
                .isEqualTo(ClaimantStatus.IN_REVIEW);

        // the hold, and who may not do what
        assertThat(pay(adjuster, exposureId).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(pay("siu1", exposureId).getStatusCode()).as("SIU can never pay").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(postIfMatch(adjuster, claimPath(claimId) + "/refer-siu", etag(adjuster, claimPath(claimId)),
                new ReasonRequest("again"), ApiError.class).getBody().code()).isEqualTo("INVALID_TRANSITION");
        assertThat(outcome(adjuster, siuCase.id(), SiuCase.Outcome.CLEARED, "fine", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(post("siu1", "/api/v1/activities/" + investigation.id() + "/complete", null, ApiError.class)
                .getBody().code()).isEqualTo("COMPLETES_AUTOMATICALLY");

        SiuCaseResponse cleared = outcome("siu1", siuCase.id(), SiuCase.Outcome.CLEARED,
                "Garage confirmed the invoice; the similar one was a quote for another car", SiuCaseResponse.class).getBody();

        assertThat(cleared.status()).isEqualTo(SiuCase.Status.CLEARED);
        assertThat(cleared.investigator().username()).isEqualTo("siu1");
        assertThat(asSupervisor(claimId).status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(pay(adjuster, exposureId).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        eventually().until(() -> activity(claimId, ActivityType.SIU_INVESTIGATION).status() == Activity.Status.COMPLETED);
        assertThat(get("siu1", claimPath(claimId), String.class).getStatusCode())
                .as("the investigator keeps access to what they investigated").isEqualTo(HttpStatus.OK);
        assertThat(outcome("siu1", siuCase.id(), SiuCase.Outcome.CONFIRMED, "changed my mind", ApiError.class)
                .getBody().code()).isEqualTo("ALREADY_DECIDED");
        assertThat(http.exchange("/api/v1/portal/notifications?size=100", HttpMethod.GET, new HttpEntity<>(headers("claimant1")),
                        new ParameterizedTypeReference<PageResponse<NotificationView>>() { }).getBody().content())
                .filteredOn(n -> claimId.equals(n.claimId()))
                .noneMatch(n -> (n.subject() + n.body()).matches("(?i).*(siu|fraud|investigat).*"));
    }

    @Test
    void confirmedFraudProposesADenialThatASupervisorDecides() {
        Long claimId = openClaim();
        String adjuster = assignedAdjuster(claimId);
        refer("supervisor1", claimId, "Third claim on this policy in a month");
        SiuCaseResponse siuCase = caseOf(claimId);
        assertThat(siuCase.referredBy().username()).isEqualTo("supervisor1");

        outcome("siu1", siuCase.id(), SiuCase.Outcome.CONFIRMED, "Photos show damage from an earlier accident",
                SiuCaseResponse.class);

        assertThat(asSupervisor(claimId).status()).isEqualTo(ClaimStatus.OPEN);
        ApprovalResponse denial = http.exchange("/api/v1/approvals?size=100", HttpMethod.GET,
                        new HttpEntity<>(headers("supervisor1")),
                        new ParameterizedTypeReference<PageResponse<ApprovalResponse>>() { }).getBody().content().stream()
                .filter(a -> claimId.equals(a.claimId()) && a.kind() == ApprovalRequest.Kind.DENIAL)
                .findFirst().orElseThrow();
        assertThat(denial.reason()).startsWith("SIU confirmed fraud: Photos show damage");
        assertThat(post(adjuster, claimPath(claimId) + "/denial-requests", new ReasonRequest("me too"), ApiError.class)
                .getBody().code()).isEqualTo("APPROVAL_PENDING");

        post("supervisor1", "/api/v1/approvals/" + denial.id() + "/approve", new DecisionRequest("agreed"),
                ApprovalResponse.class);

        StaffClaimResponse denied = asSupervisor(claimId);
        assertThat(denied.status()).isEqualTo(ClaimStatus.CLOSED);
        assertThat(denied.closeOutcome()).isEqualTo(CloseOutcome.DENIED);
    }

    @Test
    void aSupervisorCanWatchTheQueueButNeverRecordAnOutcome() {
        Long claimId = openClaim();
        refer(assignedAdjuster(claimId), claimId, "Inconsistent statements");
        Long caseId = caseOf(claimId).id();

        assertThat(get("supervisor1", "/api/v1/siu/cases/" + caseId, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outcome("supervisor1", caseId, SiuCase.Outcome.CLEARED, "looks fine", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(assignedAdjuster(claimId), "/api/v1/siu/cases/" + caseId, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outcome("siu1", caseId, SiuCase.Outcome.CLEARED, " ", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
