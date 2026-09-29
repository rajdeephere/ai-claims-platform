package com.claimsai.activity;

import com.claimsai.activity.api.ActivityDtos.ActivityResponse;
import com.claimsai.activity.api.ActivityDtos.CompleteRequest;
import com.claimsai.activity.api.ActivityDtos.DashboardResponse;
import com.claimsai.activity.app.ActivityService;
import com.claimsai.activity.domain.Activity;
import com.claimsai.activity.domain.ActivityType;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.ReasonRequest;
import com.claimsai.claim.api.ClaimDtos.ReassignRequest;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.claim.domain.LossType;
import com.claimsai.common.error.ApiError;
import com.claimsai.common.web.PageResponse;
import com.claimsai.financials.api.FinancialsDtos.CreateExposureRequest;
import com.claimsai.financials.api.FinancialsDtos.PaymentRequest;
import com.claimsai.financials.api.FinancialsDtos.PaymentResponse;
import com.claimsai.financials.api.FinancialsDtos.ReserveResponse;
import com.claimsai.financials.app.PaymentIssuing;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.financials.domain.Payment;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Design F11: tasks created from what happens, owned by a person or a queue, escalated when overdue. */
class ActivityIT extends IntegrationTest {

    private String claim(Long claimId) {
        return "/api/v1/claims/" + claimId;
    }

    private List<ActivityResponse> activities(Long claimId) {
        return List.of(get("supervisor1", claim(claimId) + "/activities", ActivityResponse[].class).getBody());
    }

    private ActivityResponse activity(Long claimId, ActivityType type) {
        return activities(claimId).stream().filter(a -> a.type() == type).reduce((a, b) -> b).orElse(null);
    }

    private ActivityResponse awaitActivity(Long claimId, ActivityType type) {
        eventually().until(() -> activity(claimId, type) != null);
        return activity(claimId, type);
    }

    private List<ActivityResponse> page(String user, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(user)),
                new ParameterizedTypeReference<PageResponse<ActivityResponse>>() { }).getBody().content();
    }

    private <T> ResponseEntity<T> complete(String user, Long activityId, String note, Class<T> type) {
        return post(user, "/api/v1/activities/" + activityId + "/complete", new CompleteRequest(note), type);
    }

    private Long userId(String username) {
        return jdbc.sql("SELECT id FROM app_user WHERE username = ?").param(username).query(Long.class).single();
    }

    @Test
    void anAssignedClaimNeedsAFirstContactAndMissingItsSlaEscalates() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));   // estimate 3,800: STANDARD
        String adjuster = assignedAdjuster(claimId);

        ActivityResponse contact = awaitActivity(claimId, ActivityType.FIRST_CONTACT);
        assertThat(contact.assignee()).isEqualTo(adjuster);
        assertThat(contact.priority()).isEqualTo(Activity.Priority.NORMAL);
        assertThat(Duration.between(contact.createdAt(), contact.dueAt())).isEqualTo(Duration.ofHours(24));
        assertThat(page(adjuster, "/api/v1/activities?size=100")).extracting(ActivityResponse::id).contains(contact.id());
        assertThat(page(adjuster, "/api/v1/activities?overdueOnly=true&size=100")).extracting(ActivityResponse::id)
                .doesNotContain(contact.id());

        // a day passes
        makeJobsDue(claimId, ActivityService.DUE_JOB);
        eventually().until(() -> activity(claimId, ActivityType.FIRST_CONTACT).escalatedAt() != null);

        assertThat(activity(claimId, ActivityType.FIRST_CONTACT).priority()).isEqualTo(Activity.Priority.URGENT);
        assertThat(List.of(get("supervisor1", claim(claimId) + "/timeline", TimelineEntryResponse[].class).getBody()))
                .filteredOn(e -> e.action().equals("SLA_BREACHED")).singleElement()
                .satisfies(e -> assertThat(e.actor()).isEqualTo("system"));
        assertThat(page("supervisor1", "/api/v1/activities/breached?size=100")).extracting(ActivityResponse::id)
                .contains(contact.id());
        DashboardResponse dashboard = get("supervisor1", "/api/v1/dashboard/supervisor", DashboardResponse.class).getBody();
        assertThat(dashboard.breachedActivities()).isPositive();
        assertThat(dashboard.breachesByOwner()).anySatisfy(o -> {
            assertThat(o.owner()).isEqualTo(adjuster);
            assertThat(o.count()).isPositive();
        });
        assertThat(get(adjuster, "/api/v1/dashboard/supervisor", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // only the owner (or a supervisor) completes it, once
        assertThat(complete(otherAdjuster(adjuster), contact.id(), "x", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(complete("siu1", contact.id(), "x", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ActivityResponse done = complete(adjuster, contact.id(), "Called, photos promised by Friday",
                ActivityResponse.class).getBody();
        assertThat(done.status()).isEqualTo(Activity.Status.COMPLETED);
        assertThat(done.completedBy()).isEqualTo(adjuster);
        assertThat(complete(adjuster, contact.id(), "again", ApiError.class).getBody().code())
                .isEqualTo("ACTIVITY_NOT_OPEN");
    }

    @Test
    void completingOnTimeCancelsTheSlaTimer() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));
        ActivityResponse contact = awaitActivity(claimId, ActivityType.FIRST_CONTACT);

        complete(assignedAdjuster(claimId), contact.id(), null, ActivityResponse.class);

        assertThat(jdbc.sql("SELECT status FROM job WHERE dedup_key = ?").param(ActivityService.DUE_JOB + ":" + contact.id())
                .query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void aFastTrackClaimIsContactedWithinFourHours() {
        var response = fileClaim("claimant1", "/api/v1/portal/claims",
                fnol(newAutoPolicy("claimant1"), LossType.VEHICLE_COLLISION, "1500", false), UUID.randomUUID().toString(),
                PortalClaimResponse.class);
        Long claimId = awaitIntake(response.getBody().id());

        ActivityResponse contact = awaitActivity(claimId, ActivityType.FIRST_CONTACT);

        assertThat(Duration.between(contact.createdAt(), contact.dueAt())).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void openWorkFollowsTheClaimAndEndsWithIt() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));
        String first = assignedAdjuster(claimId);
        String second = otherAdjuster(first);
        awaitActivity(claimId, ActivityType.FIRST_CONTACT);

        postIfMatch("supervisor1", claim(claimId) + "/reassign", etag("supervisor1", claim(claimId)),
                new ReassignRequest(userId(second), "workload"), String.class);

        eventually().until(() -> second.equals(activity(claimId, ActivityType.FIRST_CONTACT).assignee()));
        assertThat(activities(claimId)).filteredOn(a -> a.type() == ActivityType.FIRST_CONTACT).hasSize(1);

        postIfMatch(second, claim(claimId) + "/close", etag(second, claim(claimId)), new ReasonRequest("resolved by phone"),
                String.class);
        eventually().until(() -> activity(claimId, ActivityType.FIRST_CONTACT).status() == Activity.Status.CANCELLED);

        postIfMatch("supervisor1", claim(claimId) + "/reopen", etag("supervisor1", claim(claimId)),
                new ReasonRequest("claimant disputes"), String.class);
        ActivityResponse review = awaitActivity(claimId, ActivityType.REVIEW_REOPENED);
        assertThat(review.assignee()).isEqualTo(second);
        assertThat(review.status()).isEqualTo(Activity.Status.OPEN);
    }

    @Test
    void aPaymentTheBankNeverConfirmsBecomesATaskForTheSupervisors() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));
        String adjuster = assignedAdjuster(claimId);
        Long exposureId = post(adjuster, claim(claimId) + "/exposures", new CreateExposureRequest(
                Exposure.Type.VEHICLE_DAMAGE, "Asha Verma", new BigDecimal("4000"), null), ReserveResponse.class)
                .getBody().exposure().id();
        HttpHeaders headers = headers(adjuster);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        PaymentResponse payment = http.exchange("/api/v1/exposures/" + exposureId + "/payments", HttpMethod.POST,
                new HttpEntity<>(new PaymentRequest(new BigDecimal("1000"), "DOWN Garage", "repair"), headers),
                PaymentResponse.class).getBody();

        // every retry (after its backoff) finds the payment platform unreachable
        eventually().atMost(Duration.ofSeconds(30)).until(() -> {
            makeJobsDue(claimId, PaymentIssuing.JOB_TYPE);
            return "FAILED".equals(jdbc.sql("SELECT status FROM job WHERE dedup_key = ?")
                    .param(PaymentIssuing.JOB_TYPE + ":" + payment.id()).query(String.class).single());
        });

        ActivityResponse task = awaitActivity(claimId, ActivityType.PAYMENT_STATUS_UNKNOWN);
        assertThat(task.candidateRole()).hasToString("SUPERVISOR");
        assertThat(task.linkedId()).isEqualTo(payment.id());
        assertThat(task.priority()).isEqualTo(Activity.Priority.URGENT);
        assertThat(task.subject()).contains("Rs 1000.00 to DOWN Garage");
        PaymentResponse stillApproved = List.of(get("supervisor1", claim(claimId) + "/payments", PaymentResponse[].class)
                .getBody()).get(0);
        assertThat(stillApproved.status()).as("the money may have left: never FAILED on a timeout")
                .isEqualTo(Payment.Status.APPROVED);
        assertThat(complete(adjuster, task.id(), "x", String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(complete("supervisor1", task.id(), "Bank confirms nothing was paid; escalated to finance",
                ActivityResponse.class).getBody().status()).isEqualTo(Activity.Status.COMPLETED);
    }
}
