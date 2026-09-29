package com.claimsai.platform;

import com.claimsai.claim.api.ClaimDtos.MessageRequest;
import com.claimsai.claim.api.ClaimDtos.WithdrawRequest;
import com.claimsai.common.error.ApiError;
import com.claimsai.common.web.PageResponse;
import com.claimsai.notification.api.PortalNotificationController.NotificationView;
import com.claimsai.notification.api.PortalNotificationController.UnreadCount;
import com.claimsai.platform.outbox.app.OutboxService;
import com.claimsai.platform.outbox.domain.OutboxEvent;
import com.claimsai.platform.outbox.infra.OutboxEventRepository;
import com.claimsai.support.IntegrationTest;
import com.claimsai.support.TestBackgroundWork;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxIT extends IntegrationTest {

    @Autowired
    private OutboxService outbox;
    @Autowired
    private OutboxEventRepository events;

    private List<NotificationView> notificationsFor(String claimant, Long claimId) {
        PageResponse<NotificationView> page = http.exchange("/api/v1/portal/notifications?size=100", HttpMethod.GET,
                new HttpEntity<>(headers(claimant)), new ParameterizedTypeReference<PageResponse<NotificationView>>() { })
                .getBody();
        return page.content().stream().filter(n -> claimId.equals(n.claimId())).toList();
    }

    private static long randomAggregateId() {
        return -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    @Test
    void theClaimantIsNotifiedOfEachStepOfTheirClaim() {
        Long id = portalClaim("claimant2", "POL-AUTO-1002");
        eventually().until(() -> notificationsFor("claimant2", id).size() == 2);
        assertThat(notificationsFor("claimant2", id)).extracting(NotificationView::subject)
                .anySatisfy(s -> assertThat(s).startsWith("We received your claim"))
                .anySatisfy(s -> assertThat(s).endsWith("is being handled"));

        String adjuster = assignedAdjuster(id);
        postIfMatch(adjuster, "/api/v1/claims/" + id + "/request-info", etag(adjuster, "/api/v1/claims/" + id),
                new MessageRequest("Please send photos of the damage"), String.class);
        eventually().until(() -> notificationsFor("claimant2", id).size() == 3);
        NotificationView question = notificationsFor("claimant2", id).get(0);   // newest first
        assertThat(question.subject()).startsWith("Action needed");
        assertThat(question.body()).isEqualTo("Please send photos of the damage");

        postIfMatch("claimant2", "/api/v1/portal/claims/" + id + "/withdraw",
                etag("claimant2", "/api/v1/portal/claims/" + id), new WithdrawRequest(null), String.class);
        eventually().until(() -> notificationsFor("claimant2", id).size() == 4);
        assertThat(notificationsFor("claimant2", id).get(0).body()).isEqualTo("You withdrew this claim.");

        // every event of the claim was published
        assertThat(events.findByAggregateTypeAndAggregateIdOrderByIdAsc("CLAIM", id))
                .isNotEmpty().allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull());
    }

    @Test
    void notificationsCanBeMarkedReadOnlyByTheirRecipient() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        eventually().until(() -> !notificationsFor("claimant1", id).isEmpty());
        Long notificationId = notificationsFor("claimant1", id).get(0).id();
        long unreadBefore = get("claimant1", "/api/v1/portal/notifications/unread-count", UnreadCount.class)
                .getBody().unread();

        assertThat(post("claimant2", "/api/v1/portal/notifications/" + notificationId + "/read", null, ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var read = post("claimant1", "/api/v1/portal/notifications/" + notificationId + "/read", null,
                NotificationView.class);

        assertThat(read.getBody().readAt()).isNotNull();
        assertThat(get("claimant1", "/api/v1/portal/notifications/unread-count", UnreadCount.class).getBody().unread())
                .isEqualTo(unreadBefore - 1);
    }

    @Test
    void anEventWrittenInARolledBackTransactionNeverExists() {
        long aggregateId = randomAggregateId();

        assertThatThrownBy(() -> inTransaction(() -> {
            outbox.append("TEST", aggregateId, TestBackgroundWork.TEST_EVENT, Map.of());
            throw new IllegalStateException("business rule failed after the event was appended");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(events.findByAggregateTypeAndAggregateIdOrderByIdAsc("TEST", aggregateId)).isEmpty();
    }

    @Test
    void aFailingListenerIsRetriedWhileListenersThatSucceededAreNotCalledAgain() {
        long aggregateId = randomAggregateId();
        inTransaction(() -> {
            outbox.append("TEST", aggregateId, TestBackgroundWork.TEST_EVENT, Map.of("failTimes", 1));
            return null;
        });
        OutboxEvent event = events.findByAggregateTypeAndAggregateIdOrderByIdAsc("TEST", aggregateId).get(0);

        eventually().until(() -> events.findById(event.getId()).orElseThrow().getAttempts() == 1);
        assertThat(events.findById(event.getId()).orElseThrow().getLastError()).contains("test-flaky");
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = :now WHERE id = :id")
                .param("now", Timestamp.from(Instant.now().minusSeconds(1))).param("id", event.getId()).update();

        eventually().until(() -> events.findById(event.getId()).orElseThrow().getPublishedAt() != null);
        assertThat(TestBackgroundWork.DELIVERIES.get("test-flaky:" + event.getId())).hasValue(2);
        assertThat(TestBackgroundWork.DELIVERIES.get("test-steady:" + event.getId())).hasValue(1);
        assertThat(jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = ?").param(event.getId())
                .query(Long.class).single()).isEqualTo(2);
    }
}
