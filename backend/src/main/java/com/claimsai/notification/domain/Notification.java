package com.claimsai.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** An in-app message to a portal user about their claim. */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private Long recipientUserId;

    @Column(name = "claim_id", updatable = false)
    private Long claimId;

    @Column(nullable = false, updatable = false, length = 200)
    private String subject;

    @Column(nullable = false, updatable = false, length = 2000)
    private String body;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private Long sourceEventId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected Notification() {
        // for JPA
    }

    public Notification(Long recipientUserId, Long claimId, String subject, String body, Long sourceEventId,
                        Instant createdAt) {
        this.recipientUserId = recipientUserId;
        this.claimId = claimId;
        this.subject = subject;
        this.body = body;
        this.sourceEventId = sourceEventId;
        this.createdAt = createdAt;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
