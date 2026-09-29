package com.claimsai.notification.app;

import com.claimsai.notification.infra.NotificationRepository;
import com.claimsai.platform.outbox.app.OutboxMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ClaimantNotifierTest {

    static OutboxMessage event(String type, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(Map.of("claimId", 7, "claimNumber", "CLM-2026-000007",
                "claimantUserId", 1));
        payload.putAll(extra);
        return new OutboxMessage(99L, "CLAIM", 7L, type, payload, "c-1", Instant.now());
    }

    static OutboxMessage status(String from, String to, String outcome) {
        Map<String, Object> extra = new HashMap<>(Map.of("from", from, "to", to));
        if (outcome != null) {
            extra.put("outcome", outcome);
        }
        return event("CLAIM_STATUS_CHANGED", extra);
    }

    @Test
    void theClaimantHearsAboutReceiptAssignmentQuestionsAndClosure() {
        assertThat(ClaimantNotifier.messageFor(event("CLAIM_SUBMITTED", Map.of()))).get()
                .extracting(ClaimantNotifier.Message::subject).isEqualTo("We received your claim CLM-2026-000007");
        assertThat(ClaimantNotifier.messageFor(status("ASSESSING", "OPEN", null))).isPresent();
        assertThat(ClaimantNotifier.messageFor(event("INFO_REQUESTED", Map.of("message", "Photos please")))).get()
                .extracting(ClaimantNotifier.Message::body).isEqualTo("Photos please");
        assertThat(ClaimantNotifier.messageFor(status("OPEN", "CLOSED", "WITHDRAWN"))).get()
                .extracting(ClaimantNotifier.Message::body).isEqualTo("You withdrew this claim.");
    }

    @Test
    void aFraudInvestigationIsNeverAnnounced() {
        assertThat(ClaimantNotifier.messageFor(status("ASSESSING", "SIU_REVIEW", null))).isEmpty();
        assertThat(ClaimantNotifier.messageFor(status("OPEN", "SIU_REVIEW", null))).isEmpty();
        // coming back from SIU looks like nothing happened
        assertThat(ClaimantNotifier.messageFor(status("SIU_REVIEW", "OPEN", null))).isEmpty();
    }

    @Test
    void awaitingInfoIsCoveredByTheQuestionItself() {
        assertThat(ClaimantNotifier.messageFor(status("OPEN", "AWAITING_INFO", null))).isEmpty();
    }

    @Test
    void aPhoneFnolWithoutAPortalUserNotifiesNobody() {
        NotificationRepository repository = mock(NotificationRepository.class);
        Map<String, Object> payload = Map.of("claimId", 7, "claimNumber", "CLM-2026-000007");

        new ClaimantNotifier(repository, Clock.systemUTC())
                .on(new OutboxMessage(1L, "CLAIM", 7L, "CLAIM_SUBMITTED", payload, null, Instant.now()));

        verify(repository, never()).save(any());
    }
}
