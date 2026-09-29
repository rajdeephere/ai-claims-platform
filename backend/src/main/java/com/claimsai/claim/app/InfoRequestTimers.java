package com.claimsai.claim.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimEvents;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.InfoRequest;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.claim.infra.InfoRequestRepository;
import com.claimsai.platform.jobs.app.JobContext;
import com.claimsai.platform.jobs.app.JobHandler;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The deadlines of an information request (design F8), as three timers in the job queue:
 * <pre>
 * request ──(3 days)── reminder to the claimant
 *         ──(7 days)── overdue: a task for the supervisors
 *         ──(14 days)─ expired: the claim comes back OPEN, the adjuster decides
 * </pre>
 * An answer, a cancellation or a withdrawal cancels the timers that haven't fired. Each timer re-reads the
 * request and does nothing if it is no longer open, so a timer that slipped through cancellation is harmless.
 */
@Service
public class InfoRequestTimers {

    public static final String REMINDER = "INFO_REQUEST_REMINDER";
    public static final String OVERDUE = "INFO_REQUEST_OVERDUE";
    public static final String EXPIRY = "INFO_REQUEST_EXPIRY";
    private static final List<String> TYPES = List.of(REMINDER, OVERDUE, EXPIRY);

    private final ClaimRepository claims;
    private final InfoRequestRepository infoRequests;
    private final ClaimAuditTrail auditTrail;
    private final JobService jobs;
    private final Duration remindAfter;
    private final Duration overdueAfter;
    private final Duration expireAfter;
    private final Clock clock;

    public InfoRequestTimers(ClaimRepository claims, InfoRequestRepository infoRequests, ClaimAuditTrail auditTrail,
                             JobService jobs,
                             @Value("${app.info-requests.remind-after}") Duration remindAfter,
                             @Value("${app.info-requests.overdue-after}") Duration overdueAfter,
                             @Value("${app.info-requests.expire-after}") Duration expireAfter, Clock clock) {
        if (!(remindAfter.compareTo(overdueAfter) < 0 && overdueAfter.compareTo(expireAfter) < 0)) {
            throw new IllegalStateException("app.info-requests: expected remind < overdue < expire");
        }
        this.claims = claims;
        this.infoRequests = infoRequests;
        this.auditTrail = auditTrail;
        this.jobs = jobs;
        this.remindAfter = remindAfter;
        this.overdueAfter = overdueAfter;
        this.expireAfter = expireAfter;
        this.clock = clock;
    }

    static String key(String type, Long infoRequestId) {
        return type + ":" + infoRequestId;
    }

    /** In the transaction that creates the request: the request and its deadlines commit together. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(InfoRequest request) {
        Map<String, Object> payload = Map.of("infoRequestId", request.getId());
        jobs.schedule(new JobRequest(REMINDER, request.getClaimId(), payload, remindAfter, key(REMINDER, request.getId())));
        jobs.schedule(new JobRequest(OVERDUE, request.getClaimId(), payload, overdueAfter, key(OVERDUE, request.getId())));
        jobs.schedule(new JobRequest(EXPIRY, request.getClaimId(), payload, expireAfter, key(EXPIRY, request.getId())));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(InfoRequest request) {
        TYPES.forEach(type -> jobs.cancel(key(type, request.getId())));
    }

    // ---- the timers ----

    @Transactional(propagation = Propagation.MANDATORY)
    public void remind(Long infoRequestId) {
        InfoRequest request = infoRequests.findById(infoRequestId).orElseThrow();
        if (request.isOpen()) {
            Claim claim = claims.findById(request.getClaimId()).orElseThrow();
            auditTrail.infoRequestTimer(claim, ClaimEvents.INFO_REQUEST_REMINDER, infoRequestId, request.getMessage());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markOverdue(Long infoRequestId) {
        InfoRequest request = infoRequests.findById(infoRequestId).orElseThrow();
        if (request.isOpen()) {
            Claim claim = claims.findById(request.getClaimId()).orElseThrow();
            auditTrail.infoRequestTimer(claim, ClaimEvents.INFO_REQUEST_OVERDUE, infoRequestId, request.getMessage());
        }
    }

    /** AWAITING_INFO -> OPEN, by the system. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expire(Long infoRequestId) {
        InfoRequest request = infoRequests.findById(infoRequestId).orElseThrow();
        Claim claim = claims.findById(request.getClaimId()).orElseThrow();
        if (!request.isOpen() || claim.getStatus() != ClaimStatus.AWAITING_INFO) {
            return;
        }
        Instant now = clock.instant();
        request.expire(now);
        var transition = claim.informationRequestExpired(now);
        auditTrail.infoRequestTimer(claim, ClaimEvents.INFO_REQUEST_EXPIRED, infoRequestId, request.getMessage());
        auditTrail.transition(claim, transition, AuditActor.SYSTEM, "no answer within " + expireAfter.toDays() + " days");
    }

    // ---- job handlers ----

    private static Long requestId(JobContext job) {
        return ((Number) job.payload().get("infoRequestId")).longValue();
    }

    @Component
    public static class ReminderJob implements JobHandler {
        private final InfoRequestTimers timers;

        public ReminderJob(InfoRequestTimers timers) {
            this.timers = timers;
        }

        @Override
        public String type() {
            return REMINDER;
        }

        @Override
        public void handle(JobContext job) {
            timers.remind(requestId(job));
        }
    }

    @Component
    public static class OverdueJob implements JobHandler {
        private final InfoRequestTimers timers;

        public OverdueJob(InfoRequestTimers timers) {
            this.timers = timers;
        }

        @Override
        public String type() {
            return OVERDUE;
        }

        @Override
        public void handle(JobContext job) {
            timers.markOverdue(requestId(job));
        }
    }

    @Component
    public static class ExpiryJob implements JobHandler {
        private final InfoRequestTimers timers;

        public ExpiryJob(InfoRequestTimers timers) {
            this.timers = timers;
        }

        @Override
        public String type() {
            return EXPIRY;
        }

        @Override
        public void handle(JobContext job) {
            timers.expire(requestId(job));
        }
    }
}
