package com.claimsai.notification.app;

import com.claimsai.claim.domain.ClaimEvents;
import com.claimsai.financials.domain.FinancialsEvents;
import com.claimsai.notification.domain.Notification;
import com.claimsai.notification.infra.NotificationRepository;
import com.claimsai.platform.outbox.app.OutboxListener;
import com.claimsai.platform.outbox.app.OutboxMessage;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * Turns claim events into messages for the claimant. Knows only the event payloads, never the claim
 * module's services or tables: the outbox is the whole contract between them.
 *
 * <p>Deliberately silent about SIU_REVIEW: a claimant must never learn they are under fraud investigation.
 */
@Component
public class ClaimantNotifier implements OutboxListener {

    private final NotificationRepository notifications;
    private final Clock clock;

    public ClaimantNotifier(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "claimant-notifier";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(ClaimEvents.CLAIM_SUBMITTED, ClaimEvents.CLAIM_STATUS_CHANGED, ClaimEvents.INFO_REQUESTED,
                ClaimEvents.INFO_REQUEST_REMINDER, ClaimEvents.INFO_REQUEST_EXPIRED, FinancialsEvents.PAYMENT_ISSUED);
    }

    @Override
    public void on(OutboxMessage event) {
        Long claimant = event.number(ClaimEvents.CLAIMANT_USER_ID);
        if (claimant == null) {
            return;   // FNOL taken by phone for someone without a portal login
        }
        messageFor(event).ifPresent(m -> notifications.save(new Notification(claimant,
                event.number(ClaimEvents.CLAIM_ID), m.subject(), m.body(), event.id(), clock.instant())));
    }

    record Message(String subject, String body) {
    }

    static Optional<Message> messageFor(OutboxMessage event) {
        String number = event.text(ClaimEvents.CLAIM_NUMBER);
        return switch (event.eventType()) {
            case ClaimEvents.CLAIM_SUBMITTED -> Optional.of(new Message("We received your claim " + number,
                    "Thank you. We are checking your policy and will assign a claims adjuster shortly."));
            case ClaimEvents.INFO_REQUESTED -> Optional.of(new Message("Action needed on claim " + number,
                    event.text(ClaimEvents.MESSAGE)));
            case ClaimEvents.INFO_REQUEST_REMINDER -> Optional.of(new Message("Reminder: action needed on claim " + number,
                    "We are still waiting for your answer: " + event.text(ClaimEvents.MESSAGE)));
            case ClaimEvents.INFO_REQUEST_EXPIRED -> Optional.of(new Message("We didn't hear back about claim " + number,
                    "Your adjuster will continue with the information we have. You can still upload documents."));
            case FinancialsEvents.PAYMENT_ISSUED -> Optional.of(new Message("Payment issued on claim " + number,
                    "A payment of Rs " + event.text(FinancialsEvents.AMOUNT) + " to " + event.text(FinancialsEvents.PAYEE)
                            + " has been issued."));
            case ClaimEvents.CLAIM_STATUS_CHANGED -> statusMessage(number, event.text(ClaimEvents.FROM),
                    event.text(ClaimEvents.TO), event.text(ClaimEvents.OUTCOME));
            default -> Optional.empty();
        };
    }

    private static Optional<Message> statusMessage(String number, String from, String to, String outcome) {
        if ("OPEN".equals(to) && "ASSESSING".equals(from)) {
            return Optional.of(new Message("Your claim " + number + " is being handled",
                    "A claims adjuster has been assigned and will contact you."));
        }
        if ("OPEN".equals(to) && "CLOSED".equals(from)) {
            return Optional.of(new Message("Your claim " + number + " was reopened", "We are looking at it again."));
        }
        if ("CLOSED".equals(to)) {
            return Optional.of(new Message("Your claim " + number + " is closed", switch (outcome) {
                case "PAID" -> "Payment has been issued.";
                case "DENIED" -> "Your claim was not accepted. The letter explains why and how to appeal.";
                case "WITHDRAWN" -> "You withdrew this claim.";
                default -> "The claim was closed without payment.";
            }));
        }
        // AWAITING_INFO is announced by INFO_REQUESTED (it has the question); SIU_REVIEW is never announced
        return Optional.empty();
    }
}
