package com.claimsai.activity.app;

import com.claimsai.activity.domain.Activity;
import com.claimsai.activity.domain.ActivityType;
import com.claimsai.activity.infra.ActivityRepository;
import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.app.ClaimAccess;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.app.UserRef;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Activities: created and closed by the system (see {@link ActivityPlanner}), completed by people, and
 * escalated by a timer when their SLA is breached (the ACTIVITY_DUE job, one per activity).
 */
@Service
public class ActivityService {

    public static final String DUE_JOB = "ACTIVITY_DUE";
    static final String ENTITY = "ACTIVITY";

    private final ActivityRepository activities;
    private final ClaimAccess claimAccess;
    private final ClaimQueryService claimQueries;
    private final UserService users;
    private final AuditService audit;
    private final JobService jobs;
    private final Clock clock;

    public ActivityService(ActivityRepository activities, ClaimAccess claimAccess, ClaimQueryService claimQueries,
                           UserService users, AuditService audit, JobService jobs, Clock clock) {
        this.activities = activities;
        this.claimAccess = claimAccess;
        this.claimQueries = claimQueries;
        this.users = users;
        this.audit = audit;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** A person, or a role's queue. */
    public record Owner(Long userId, Role role) {

        public static Owner person(Long userId) {
            return new Owner(Objects.requireNonNull(userId), null);
        }

        public static Owner queue(Role role) {
            return new Owner(null, role);
        }
    }

    /** An activity with the names a screen shows. */
    public record ActivityView(Activity activity, String claimNumber, UserRef assignee, UserRef completedBy) {
    }

    static String dueKey(Long activityId) {
        return DUE_JOB + ":" + activityId;
    }

    // ---------- the system's side ----------

    /**
     * Opens the activity unless the same one is already open (the planner may see an event twice).
     *
     * @param linkedId the record it is about (info request, SIU case, payment), or null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Activity> open(Long claimId, ActivityType type, String subject, Owner owner, Duration sla,
                                   Long linkedId) {
        if (activities.findByKey(claimId, type, linkedKey(linkedId), Activity.Status.OPEN).isPresent()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Activity activity = activities.save(Activity.open(claimId, type, subject, owner.userId(), owner.role(),
                now.plus(sla), linkedId, now));
        jobs.schedule(new JobRequest(DUE_JOB, claimId, Map.of("activityId", activity.getId()), sla,
                dueKey(activity.getId())));
        Map<String, Object> after = new HashMap<>();
        after.put("activityId", activity.getId());
        after.put("type", type.name());
        after.put("owner", ownerName(owner));
        after.put("dueAt", activity.getDueAt().toString());
        audit.record(ENTITY, activity.getId(), claimId, "ACTIVITY_CREATED", AuditActor.SYSTEM, null, after, subject);
        return Optional.of(activity);
    }

    /** The work behind an activity was done elsewhere, or is no longer needed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeOpen(Long claimId, Set<ActivityType> types, Long linkedId, boolean completed, String note) {
        Instant now = clock.instant();
        for (Activity a : activities.findByClaimIdAndStatus(claimId, Activity.Status.OPEN)) {
            if (types.contains(a.getType()) && (linkedId == null || linkedId.equals(a.getLinkedId()))) {
                if (completed) {
                    a.completeBySystem(note, now);
                } else {
                    a.cancel(note, now);
                }
                jobs.cancel(dueKey(a.getId()));
                audit.record(ENTITY, a.getId(), claimId, completed ? "ACTIVITY_COMPLETED" : "ACTIVITY_CANCELLED",
                        AuditActor.SYSTEM, Map.of("status", "OPEN"),
                        Map.of("activityId", a.getId(), "type", a.getType().name(), "status", a.getStatus().name()), note);
            }
        }
    }

    /** The claim was (re)assigned: the adjuster's open work follows the claim. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void moveAdjusterWork(Long claimId, Long adjusterId) {
        for (Activity a : activities.findByClaimIdAndStatus(claimId, Activity.Status.OPEN)) {
            if (a.getType().owner() == ActivityType.Owner.ADJUSTER && !adjusterId.equals(a.getAssigneeId())) {
                Map<String, Object> before = new HashMap<>();
                before.put("assigneeId", a.getAssigneeId());
                a.assignTo(adjusterId);
                audit.record(ENTITY, a.getId(), claimId, "ACTIVITY_REASSIGNED", AuditActor.SYSTEM, before,
                        Map.of("activityId", a.getId(), "assigneeId", adjusterId), "claim reassigned");
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean everHad(Long claimId, ActivityType type) {
        return activities.existsByClaimIdAndType(claimId, type);
    }

    /** The ACTIVITY_DUE timer: still open at its due time means the SLA was breached. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void escalateIfOpen(Long activityId) {
        Activity a = activities.findById(activityId).orElseThrow();
        if (a.escalate(clock.instant())) {
            audit.record(ENTITY, a.getId(), a.getClaimId(), "SLA_BREACHED", AuditActor.SYSTEM, null,
                    Map.of("activityId", a.getId(), "type", a.getType().name(), "dueAt", a.getDueAt().toString(),
                            "priority", a.getPriority().name()), "not completed by its due time");
        }
    }

    // ---------- people ----------

    /**
     * Checked in the usual order: the claim visible (404) -> the activity mine to complete (403) -> one a
     * person can complete, still open (422 / 409).
     */
    @Transactional
    public ActivityView complete(Long activityId, String note, CurrentUser user) {
        Activity a = activities.findById(activityId).orElseThrow(() -> notFound(activityId));
        try {
            claimAccess.loadVisible(a.getClaimId(), user);
        } catch (NotFoundException hidden) {
            throw notFound(activityId);
        }
        if (!a.isOwnedBy(user.id(), user.role()) && user.role() != Role.SUPERVISOR) {
            throw new AccessDeniedException("Activity " + activityId + " belongs to someone else");
        }
        if (!a.getType().completedByPerson()) {
            throw new BusinessRuleException("COMPLETES_AUTOMATICALLY",
                    a.getType() + " is completed by the work itself (e.g. recording the SIU outcome)");
        }
        a.complete(user.id(), note, clock.instant());
        jobs.cancel(dueKey(a.getId()));
        audit.record(ENTITY, a.getId(), a.getClaimId(), "ACTIVITY_COMPLETED", new AuditActor(user.id(), user.username()),
                Map.of("status", "OPEN"), Map.of("activityId", a.getId(), "type", a.getType().name(), "status", "COMPLETED"),
                note);
        activities.flush();   // a concurrent completion fails here (version): 409
        return views(List.of(a)).get(0);
    }

    /** My work, soonest due first: assigned to me or in my role's queue. */
    @Transactional(readOnly = true)
    public Page<ActivityView> mine(CurrentUser user, boolean overdueOnly, Pageable page) {
        Page<Activity> result = overdueOnly
                ? activities.findOwnedByDueBefore(user.id(), user.role(), Activity.Status.OPEN, clock.instant(), page)
                : activities.findOwnedBy(user.id(), user.role(), Activity.Status.OPEN, page);
        return withViews(result);
    }

    /** For supervisors: every open activity whose SLA was breached. */
    @Transactional(readOnly = true)
    public Page<ActivityView> breached(Pageable page) {
        return withViews(activities.findByStatusAndEscalatedAtIsNotNull(Activity.Status.OPEN, page));
    }

    @Transactional(readOnly = true)
    public List<ActivityView> forClaim(Long claimId, CurrentUser user) {
        return views(activities.findByClaimIdOrderByIdAsc(claimAccess.loadVisible(claimId, user).getId()));
    }

    // ---------- helpers ----------

    private Page<ActivityView> withViews(Page<Activity> page) {
        List<ActivityView> views = views(page.getContent());
        Map<Long, ActivityView> byId = new HashMap<>();
        views.forEach(v -> byId.put(v.activity().getId(), v));
        return page.map(a -> byId.get(a.getId()));
    }

    private List<ActivityView> views(Collection<Activity> list) {
        Map<Long, String> numbers = claimQueries.claimNumbers(list.stream().map(Activity::getClaimId).distinct().toList());
        Map<Long, UserRef> people = users.refs(list.stream()
                .flatMap(a -> java.util.stream.Stream.of(a.getAssigneeId(), a.getCompletedBy()))
                .filter(Objects::nonNull).distinct().toList());
        return list.stream().map(a -> new ActivityView(a, numbers.get(a.getClaimId()), people.get(a.getAssigneeId()),
                people.get(a.getCompletedBy()))).toList();
    }

    private String ownerName(Owner owner) {
        return owner.userId() != null ? users.ref(owner.userId()).username() : "queue:" + owner.role();
    }

    private static long linkedKey(Long linkedId) {
        return linkedId == null ? 0L : linkedId;
    }

    private static NotFoundException notFound(Long activityId) {
        return new NotFoundException("ACTIVITY_NOT_FOUND", "Activity " + activityId + " not found");
    }
}
