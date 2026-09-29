package com.claimsai.activity.domain;

import com.claimsai.common.error.ConflictException;
import com.claimsai.identity.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;

/**
 * A task on a claim, owned by one person (assignee) or by everyone with a role (candidate role), due at a
 * time. OPEN until someone completes it or the system closes it; escalated once when its SLA is breached.
 */
@Entity
@Table(name = "activity")
public class Activity {

    public enum Status { OPEN, COMPLETED, CANCELLED }

    public enum Priority { NORMAL, HIGH, URGENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private ActivityType type;

    @Column(nullable = false, updatable = false, length = 200)
    private String subject;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_role", length = 12)
    private Role candidateRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(name = "due_at", nullable = false, updatable = false)
    private Instant dueAt;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "linked_id", updatable = false)
    private Long linkedId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "outcome_note", length = 2000)
    private String outcomeNote;

    @Version
    private long version;

    protected Activity() {
        // for JPA
    }

    /** Exactly one of assigneeId and candidateRole. */
    public static Activity open(Long claimId, ActivityType type, String subject, Long assigneeId, Role candidateRole,
                                Instant dueAt, Long linkedId, Instant now) {
        if ((assigneeId == null) == (candidateRole == null)) {
            throw new IllegalArgumentException("An activity is owned by a person or by a role, not both or neither");
        }
        Activity a = new Activity();
        a.claimId = claimId;
        a.type = type;
        a.subject = subject;
        a.assigneeId = assigneeId;
        a.candidateRole = candidateRole;
        a.priority = type.priority();
        a.status = Status.OPEN;
        a.dueAt = dueAt;
        a.linkedId = linkedId;
        a.createdAt = now;
        return a;
    }

    /** Whether this user works on it: assigned to them, or in their role's queue. */
    public boolean isOwnedBy(Long userId, Role role) {
        return Objects.equals(assigneeId, userId) || (candidateRole != null && candidateRole == role);
    }

    public void complete(Long userId, String note, Instant now) {
        close(Status.COMPLETED, userId, note, now);
    }

    /** The work was done elsewhere (e.g. the SIU outcome was recorded). */
    public void completeBySystem(String note, Instant now) {
        close(Status.COMPLETED, null, note, now);
    }

    /** No longer needed (e.g. the claim was closed). */
    public void cancel(String note, Instant now) {
        close(Status.CANCELLED, null, note, now);
    }

    /**
     * The SLA was breached: raise the priority, once.
     *
     * @return false if it was closed or escalated already
     */
    public boolean escalate(Instant now) {
        if (status != Status.OPEN || escalatedAt != null) {
            return false;
        }
        escalatedAt = now;
        priority = Priority.URGENT;
        return true;
    }

    /** The claim moved to another adjuster: their work goes with it. */
    public void assignTo(Long adjusterId) {
        requireOpen();
        assigneeId = Objects.requireNonNull(adjusterId);
        candidateRole = null;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    private void close(Status target, Long userId, String note, Instant now) {
        requireOpen();
        status = target;
        completedBy = userId;
        outcomeNote = note;
        completedAt = now;
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new ConflictException("ACTIVITY_NOT_OPEN", "Activity " + id + " is already " + status);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public ActivityType getType() {
        return type;
    }

    public String getSubject() {
        return subject;
    }

    public Long getAssigneeId() {
        return assigneeId;
    }

    public Role getCandidateRole() {
        return candidateRole;
    }

    public Priority getPriority() {
        return priority;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public Long getLinkedId() {
        return linkedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getCompletedBy() {
        return completedBy;
    }

    public String getOutcomeNote() {
        return outcomeNote;
    }

    public long getVersion() {
        return version;
    }
}
