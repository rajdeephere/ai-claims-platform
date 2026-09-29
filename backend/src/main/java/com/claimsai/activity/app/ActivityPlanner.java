package com.claimsai.activity.app;

import com.claimsai.activity.app.ActivityService.Owner;
import com.claimsai.activity.domain.ActivityType;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.claim.domain.ClaimEvents;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.financials.domain.FinancialsEvents;
import com.claimsai.identity.domain.Role;
import com.claimsai.platform.outbox.app.OutboxListener;
import com.claimsai.platform.outbox.app.OutboxMessage;
import com.claimsai.siu.domain.SiuEvents;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Turns what happened into work for people. Every activity starts from an outbox event, so no module has
 * to know that activities exist: the claim, SIU and financials modules just publish what they did, and
 * this listener (at-most-once per event, in its own transaction) opens, moves and closes the tasks.
 *
 * <pre>
 * CLAIM_ASSIGNED                 first contact for the adjuster (4 h fast-track, else 24 h); open work moves
 * HIGH_FRAUD_SCORE               "consider an SIU referral" for the adjuster
 * INFO_REQUEST_OVERDUE           follow-up for the supervisors
 * INFO_REQUEST_EXPIRED           "no answer: decide" for the adjuster
 * SIU_CASE_OPENED / _DECIDED     investigation for the SIU queue / completed by the outcome
 * PAYMENT_STUCK                  "status unknown" for the supervisors; closed when the payment resolves
 * status: -> CLOSED              everything open is cancelled
 * status: AWAITING_INFO -> any   the follow-up is cancelled
 * status: CLOSED -> OPEN         "review the reopened claim" for the adjuster
 * </pre>
 */
@Component
public class ActivityPlanner implements OutboxListener {

    private final ActivityService activities;
    private final ClaimQueryService claims;

    public ActivityPlanner(ActivityService activities, ClaimQueryService claims) {
        this.activities = activities;
        this.claims = claims;
    }

    @Override
    public String name() {
        return "activity-planner";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(ClaimEvents.CLAIM_ASSIGNED, ClaimEvents.CLAIM_STATUS_CHANGED, ClaimEvents.INFO_REQUEST_OVERDUE,
                ClaimEvents.INFO_REQUEST_EXPIRED, ClaimEvents.HIGH_FRAUD_SCORE, SiuEvents.SIU_CASE_OPENED,
                SiuEvents.SIU_CASE_DECIDED, FinancialsEvents.PAYMENT_STUCK, FinancialsEvents.PAYMENT_ISSUED,
                FinancialsEvents.PAYMENT_FAILED);
    }

    @Override
    public void on(OutboxMessage event) {
        Long claimId = event.number(ClaimEvents.CLAIM_ID);
        switch (event.eventType()) {
            case ClaimEvents.CLAIM_ASSIGNED -> assigned(claimId, event);
            case ClaimEvents.CLAIM_STATUS_CHANGED -> statusChanged(claimId, event);
            case ClaimEvents.INFO_REQUEST_OVERDUE -> open(claimId, ActivityType.INFO_OVERDUE, Owner.queue(Role.SUPERVISOR),
                    event.number(ClaimEvents.INFO_REQUEST_ID));
            case ClaimEvents.INFO_REQUEST_EXPIRED -> open(claimId, ActivityType.INFO_NO_RESPONSE, adjusterOf(event),
                    event.number(ClaimEvents.INFO_REQUEST_ID));
            case ClaimEvents.HIGH_FRAUD_SCORE -> open(claimId, ActivityType.CONSIDER_SIU_REFERRAL, adjusterOf(event), null);
            case SiuEvents.SIU_CASE_OPENED -> {
                activities.closeOpen(claimId, EnumSet.of(ActivityType.CONSIDER_SIU_REFERRAL), null, true,
                        "referred to SIU");
                open(claimId, ActivityType.SIU_INVESTIGATION, Owner.queue(Role.SIU), event.number(SiuEvents.CASE_ID));
            }
            case SiuEvents.SIU_CASE_DECIDED -> activities.closeOpen(claimId, EnumSet.of(ActivityType.SIU_INVESTIGATION),
                    event.number(SiuEvents.CASE_ID), true, "outcome recorded: " + event.text(SiuEvents.OUTCOME));
            case FinancialsEvents.PAYMENT_STUCK -> openStuckPayment(claimId, event);
            case FinancialsEvents.PAYMENT_ISSUED, FinancialsEvents.PAYMENT_FAILED -> activities.closeOpen(claimId,
                    EnumSet.of(ActivityType.PAYMENT_STATUS_UNKNOWN), event.number(FinancialsEvents.PAYMENT_ID), true,
                    FinancialsEvents.PAYMENT_ISSUED.equals(event.eventType()) ? "the payment was issued"
                            : "the payment platform refused the payment");
            default -> { }
        }
    }

    private void assigned(Long claimId, OutboxMessage event) {
        Long adjuster = event.number(ClaimEvents.ASSIGNED_ADJUSTER_ID);
        activities.moveAdjusterWork(claimId, adjuster);
        if (!activities.everHad(claimId, ActivityType.FIRST_CONTACT)) {
            Duration sla = Segment.FAST_TRACK.name().equals(event.text(ClaimEvents.SEGMENT))
                    ? ActivityType.FAST_TRACK_FIRST_CONTACT : ActivityType.FIRST_CONTACT.sla();
            open(claimId, ActivityType.FIRST_CONTACT, Owner.person(adjuster), sla, null, ActivityType.FIRST_CONTACT.subject());
        }
    }

    private void statusChanged(Long claimId, OutboxMessage event) {
        String from = event.text(ClaimEvents.FROM);
        String to = event.text(ClaimEvents.TO);
        if (ClaimStatus.CLOSED.name().equals(to)) {
            activities.closeOpen(claimId, EnumSet.allOf(ActivityType.class), null, false, "claim closed");
        } else if (ClaimStatus.AWAITING_INFO.name().equals(from)) {
            activities.closeOpen(claimId, EnumSet.of(ActivityType.INFO_OVERDUE), null, false,
                    "the claim is no longer waiting for the claimant");
        }
        if (ClaimStatus.CLOSED.name().equals(from) && ClaimStatus.OPEN.name().equals(to)) {
            open(claimId, ActivityType.REVIEW_REOPENED, adjusterOf(event), null);
        }
    }

    private void openStuckPayment(Long claimId, OutboxMessage event) {
        String amount = event.text(FinancialsEvents.AMOUNT);   // "1000.00": money travels as a string
        String subject = ActivityType.PAYMENT_STATUS_UNKNOWN.subject() + " (Rs " + amount + " to "
                + event.text(FinancialsEvents.PAYEE) + ")";
        open(claimId, ActivityType.PAYMENT_STATUS_UNKNOWN, Owner.queue(Role.SUPERVISOR),
                ActivityType.PAYMENT_STATUS_UNKNOWN.sla(), event.number(FinancialsEvents.PAYMENT_ID), trim(subject));
    }

    private void open(Long claimId, ActivityType type, Owner owner, Long linkedId) {
        open(claimId, type, owner, type.sla(), linkedId, type.subject());
    }

    /** Never on a closed claim: an event processed late must not create work nobody can do. */
    private void open(Long claimId, ActivityType type, Owner owner, Duration sla, Long linkedId, String subject) {
        if (claims.facts(claimId).status() != ClaimStatus.CLOSED) {
            activities.open(claimId, type, subject, owner, sla, linkedId);
        }
    }

    /** The assigned adjuster; the supervisors' queue while nobody is assigned. */
    private static Owner adjusterOf(OutboxMessage event) {
        Long adjuster = event.number(ClaimEvents.ASSIGNED_ADJUSTER_ID);
        return adjuster != null ? Owner.person(adjuster) : Owner.queue(Role.SUPERVISOR);
    }

    private static String trim(String subject) {
        return subject.length() <= 200 ? subject : subject.substring(0, 197) + "...";
    }
}
