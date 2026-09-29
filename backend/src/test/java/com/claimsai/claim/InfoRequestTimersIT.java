package com.claimsai.claim;

import com.claimsai.activity.api.ActivityDtos.ActivityResponse;
import com.claimsai.activity.domain.Activity;
import com.claimsai.activity.domain.ActivityType;
import com.claimsai.claim.api.ClaimDtos.MessageRequest;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.ReasonRequest;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.claim.api.ClaimDtos.WithdrawRequest;
import com.claimsai.claim.app.InfoRequestTimers;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.common.web.PageResponse;
import com.claimsai.notification.api.PortalNotificationController.NotificationView;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Design F8: the reminder, the escalation and the deadline of an unanswered information request. */
class InfoRequestTimersIT extends IntegrationTest {

    private static final List<String> TIMERS = List.of(InfoRequestTimers.REMINDER, InfoRequestTimers.OVERDUE,
            InfoRequestTimers.EXPIRY);

    private String staff(Long claimId) {
        return "/api/v1/claims/" + claimId;
    }

    private String portal(Long claimId) {
        return "/api/v1/portal/claims/" + claimId;
    }

    private Long awaitingInfo(Long claimId, String adjuster) {
        StaffClaimResponse asked = postIfMatch(adjuster, staff(claimId) + "/request-info", etag(adjuster, staff(claimId)),
                new MessageRequest("Please upload photos of the rear bumper"), StaffClaimResponse.class).getBody();
        assertThat(asked.status()).isEqualTo(ClaimStatus.AWAITING_INFO);
        return asked.openInfoRequest().id();
    }

    private List<String> timerStatuses(Long claimId) {
        return jdbc.sql("SELECT status FROM job WHERE claim_id = :claimId AND type IN (:types) ORDER BY type")
                .param("claimId", claimId).param("types", TIMERS).query(String.class).list();
    }

    private List<ActivityResponse> activities(Long claimId) {
        return List.of(get("supervisor1", staff(claimId) + "/activities", ActivityResponse[].class).getBody());
    }

    private ActivityResponse activity(Long claimId, ActivityType type) {
        return activities(claimId).stream().filter(a -> a.type() == type).findFirst().orElse(null);
    }

    private List<NotificationView> notifications(String claimant, Long claimId) {
        return http.exchange("/api/v1/portal/notifications?size=100", HttpMethod.GET, new HttpEntity<>(headers(claimant)),
                        new ParameterizedTypeReference<PageResponse<NotificationView>>() { }).getBody().content()
                .stream().filter(n -> claimId.equals(n.claimId())).toList();
    }

    @Test
    void anUnansweredQuestionIsRemindedEscalatedAndFinallyReturnedToTheAdjuster() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));
        String adjuster = assignedAdjuster(claimId);
        Long requestId = awaitingInfo(claimId, adjuster);

        // three timers, days apart
        List<Instant> due = jdbc.sql("SELECT due_at FROM job WHERE claim_id = :claimId AND type IN (:types) ORDER BY due_at")
                .param("claimId", claimId).param("types", TIMERS).query(java.sql.Timestamp.class).list().stream()
                .map(java.sql.Timestamp::toInstant).toList();
        assertThat(due).hasSize(3);
        assertThat(Duration.between(due.get(0), due.get(2))).isCloseTo(Duration.ofDays(11), Duration.ofMinutes(1));

        // 3 days: the claimant is reminded
        makeJobsDue(claimId, InfoRequestTimers.REMINDER);
        eventually().until(() -> notifications("claimant1", claimId).stream()
                .anyMatch(n -> n.subject().startsWith("Reminder: action needed")));

        // 7 days: the supervisors follow up
        makeJobsDue(claimId, InfoRequestTimers.OVERDUE);
        eventually().until(() -> activity(claimId, ActivityType.INFO_OVERDUE) != null);
        ActivityResponse followUp = activity(claimId, ActivityType.INFO_OVERDUE);
        assertThat(followUp.candidateRole()).hasToString("SUPERVISOR");
        assertThat(followUp.linkedId()).isEqualTo(requestId);
        assertThat(http.exchange("/api/v1/activities?size=100", HttpMethod.GET, new HttpEntity<>(headers("supervisor1")),
                        new ParameterizedTypeReference<PageResponse<ActivityResponse>>() { }).getBody().content())
                .extracting(ActivityResponse::id).contains(followUp.id());

        // 14 days: the claim comes back to the adjuster, who decides
        makeJobsDue(claimId, InfoRequestTimers.EXPIRY);
        eventually().until(() -> asSupervisor(claimId).status() == ClaimStatus.OPEN);
        assertThat(asSupervisor(claimId).openInfoRequest()).isNull();
        assertThat(jdbc.sql("SELECT status FROM info_request WHERE id = ?").param(requestId).query(String.class).single())
                .isEqualTo("EXPIRED");
        TimelineEntryResponse returned = List.of(get("supervisor1", staff(claimId) + "/timeline",
                        TimelineEntryResponse[].class).getBody()).stream()
                .filter(e -> e.action().equals("STATUS_CHANGED")).reduce((a, b) -> b).orElseThrow();
        assertThat(returned.actor()).isEqualTo("system");
        assertThat(returned.text()).isEqualTo("no answer within 14 days");

        eventually().until(() -> activity(claimId, ActivityType.INFO_NO_RESPONSE) != null
                && activity(claimId, ActivityType.INFO_OVERDUE).status() == Activity.Status.CANCELLED);
        assertThat(activity(claimId, ActivityType.INFO_NO_RESPONSE).assignee()).isEqualTo(adjuster);
        eventually().until(() -> notifications("claimant1", claimId).stream()
                .anyMatch(n -> n.subject().startsWith("We didn't hear back")));
        assertThat(get("claimant1", portal(claimId), PortalClaimResponse.class).getBody().status())
                .isEqualTo(ClaimantStatus.IN_REVIEW);
    }

    @Test
    void anAnswerCancelsTheDeadlines() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));
        awaitingInfo(claimId, assignedAdjuster(claimId));

        postIfMatch("claimant1", portal(claimId) + "/respond", etag("claimant1", portal(claimId)),
                new MessageRequest("Uploaded three photos"), PortalClaimResponse.class);

        assertThat(timerStatuses(claimId)).containsOnly("CANCELLED").hasSize(3);
        assertThat(asSupervisor(claimId).status()).isEqualTo(ClaimStatus.OPEN);
    }

    @Test
    void theAdjusterCanWithdrawTheQuestion() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));
        String adjuster = assignedAdjuster(claimId);
        awaitingInfo(claimId, adjuster);

        assertThat(postIfMatch(otherAdjuster(adjuster), staff(claimId) + "/cancel-info-request", "\"0\"",
                new ReasonRequest("x"), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(postIfMatch("supervisor1", staff(claimId) + "/cancel-info-request", etag("supervisor1", staff(claimId)),
                new ReasonRequest("x"), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        StaffClaimResponse back = postIfMatch(adjuster, staff(claimId) + "/cancel-info-request",
                etag(adjuster, staff(claimId)), new ReasonRequest("Claimant sent the photos by e-mail"),
                StaffClaimResponse.class).getBody();

        assertThat(back.status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(back.openInfoRequest()).isNull();
        assertThat(timerStatuses(claimId)).containsOnly("CANCELLED");
        assertThat(List.of(get("supervisor1", staff(claimId) + "/timeline", TimelineEntryResponse[].class).getBody()))
                .extracting(TimelineEntryResponse::action).contains("INFO_REQUEST_CANCELLED");
    }

    @Test
    void withdrawingTheClaimCancelsTheDeadlinesAndTheOpenWork() {
        Long claimId = portalClaim("claimant1", newAutoPolicy("claimant1"));
        awaitingInfo(claimId, assignedAdjuster(claimId));
        eventually().until(() -> activity(claimId, ActivityType.FIRST_CONTACT) != null);

        postIfMatch("claimant1", portal(claimId) + "/withdraw", etag("claimant1", portal(claimId)),
                new WithdrawRequest("Repaired it myself"), PortalClaimResponse.class);

        assertThat(timerStatuses(claimId)).containsOnly("CANCELLED");
        eventually().until(() -> activities(claimId).stream().noneMatch(a -> a.status() == Activity.Status.OPEN));
        assertThat(activity(claimId, ActivityType.FIRST_CONTACT).outcomeNote()).isEqualTo("claim closed");
        assertThat(activity(claimId, ActivityType.FIRST_CONTACT).completedAt())
                .isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }
}
