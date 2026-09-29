package com.claimsai.activity.domain;

import com.claimsai.activity.domain.Activity.Priority;

import java.time.Duration;

/**
 * The kinds of task the system creates, each with its owner, SLA and priority (design F11). An activity is
 * always created by the system in reaction to an event; people complete them.
 */
public enum ActivityType {

    FIRST_CONTACT("Contact the claimant", Owner.ADJUSTER, Duration.ofHours(24), Priority.NORMAL, true),
    CONSIDER_SIU_REFERRAL("The fraud score reached the SIU threshold: decide whether to refer the claim",
            Owner.ADJUSTER, Duration.ofDays(1), Priority.HIGH, true),
    INFO_OVERDUE("The claimant hasn't answered the information request: follow up", Owner.SUPERVISORS,
            Duration.ofDays(1), Priority.HIGH, true),
    INFO_NO_RESPONSE("No answer to the information request: decide how to proceed", Owner.ADJUSTER,
            Duration.ofDays(2), Priority.HIGH, true),
    /** Completed by recording the case outcome, never by hand. */
    SIU_INVESTIGATION("Investigate the SIU referral", Owner.SIU, Duration.ofDays(5), Priority.HIGH, false),
    PAYMENT_STATUS_UNKNOWN("Payment status unknown: check with the bank before taking any action", Owner.SUPERVISORS,
            Duration.ofDays(1), Priority.URGENT, true),
    REVIEW_REOPENED("Review the reopened claim", Owner.ADJUSTER, Duration.ofDays(2), Priority.NORMAL, true);

    /** Who works on it. */
    public enum Owner {
        /** The claim's assigned adjuster (the supervisors' queue while nobody is assigned). */
        ADJUSTER,
        SUPERVISORS,
        SIU
    }

    /** First contact on a fast-track claim is due sooner. */
    public static final Duration FAST_TRACK_FIRST_CONTACT = Duration.ofHours(4);

    private final String subject;
    private final Owner owner;
    private final Duration sla;
    private final Priority priority;
    private final boolean completedByPerson;

    ActivityType(String subject, Owner owner, Duration sla, Priority priority, boolean completedByPerson) {
        this.subject = subject;
        this.owner = owner;
        this.sla = sla;
        this.priority = priority;
        this.completedByPerson = completedByPerson;
    }

    public String subject() {
        return subject;
    }

    public Owner owner() {
        return owner;
    }

    public Duration sla() {
        return sla;
    }

    public Priority priority() {
        return priority;
    }

    public boolean completedByPerson() {
        return completedByPerson;
    }
}
