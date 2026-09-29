package com.claimsai.claim.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.ClaimFinancialsPort;
import com.claimsai.claim.domain.ClaimNote;
import com.claimsai.claim.domain.ClaimTransition;
import com.claimsai.claim.domain.InfoRequest;
import com.claimsai.claim.domain.SiuReferralPort;
import com.claimsai.claim.infra.ClaimNoteRepository;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.claim.infra.InfoRequestRepository;
import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.web.ETags;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.app.UserRef;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Commands on an existing claim. Each one, in one transaction:
 * visible (else 404) -> permitted (else 403) -> If-Match matches (428 / 412) -> domain method (409 if the
 * status doesn't allow it) -> audit -> flush, so the response carries the new version as its ETag.
 */
@Service
@Transactional
public class ClaimCommandService {

    private final ClaimRepository claims;
    private final InfoRequestRepository infoRequests;
    private final ClaimNoteRepository notes;
    private final ClaimAccess access;
    private final ClaimAuditTrail auditTrail;
    private final UserService users;
    private final ClaimFinancialsPort financials;
    private final SiuReferralPort siu;
    private final InfoRequestTimers timers;
    private final Clock clock;

    public ClaimCommandService(ClaimRepository claims, InfoRequestRepository infoRequests, ClaimNoteRepository notes,
                               ClaimAccess access, ClaimAuditTrail auditTrail, UserService users,
                               ClaimFinancialsPort financials, SiuReferralPort siu, InfoRequestTimers timers,
                               Clock clock) {
        this.claims = claims;
        this.infoRequests = infoRequests;
        this.notes = notes;
        this.access = access;
        this.auditTrail = auditTrail;
        this.users = users;
        this.financials = financials;
        this.siu = siu;
        this.timers = timers;
        this.clock = clock;
    }

    public Claim requestInformation(Long claimId, String ifMatch, String message, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.REQUEST_INFO, user);
        Instant now = clock.instant();
        ClaimTransition transition = claim.requestInformation(now);
        // Parent first (BUG-003): the version-checked UPDATE of the claim must run before the child INSERT.
        // Otherwise the INSERT (immediate, IDENTITY id) runs first, and a concurrent request that lost the
        // race hits the one-open-request unique index: a 500 instead of a clean 409.
        claims.flush();
        InfoRequest request = infoRequests.save(new InfoRequest(claim.getId(), message, user.id(), now));
        timers.schedule(request);
        auditTrail.infoRequested(claim, request.getId(), message, actor(user));
        auditTrail.transition(claim, transition, actor(user), null);
        return flushed(claim);
    }

    public Claim respond(Long claimId, String ifMatch, String message, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.RESPOND, user);
        Instant now = clock.instant();
        ClaimTransition transition = claim.informationReceived(now);
        InfoRequest request = openRequest(claim);
        request.answer(message, user.id(), now);
        timers.cancel(request);
        auditTrail.event(claim, "INFO_RECEIVED", actor(user), null, Map.of("infoRequestId", request.getId()), message);
        auditTrail.transition(claim, transition, actor(user), null);
        return flushed(claim);
    }

    /** The adjuster withdraws their question (e.g. the claimant answered by phone). */
    public Claim cancelInformationRequest(Long claimId, String ifMatch, String reason, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.CANCEL_INFO_REQUEST, user);
        Instant now = clock.instant();
        ClaimTransition transition = claim.informationRequestCancelled(now);
        InfoRequest request = openRequest(claim);
        request.cancel(now);
        timers.cancel(request);
        auditTrail.event(claim, "INFO_REQUEST_CANCELLED", actor(user), null, Map.of("infoRequestId", request.getId()),
                reason);
        auditTrail.transition(claim, transition, actor(user), reason);
        return flushed(claim);
    }

    /** OPEN -> SIU_REVIEW by a person (the triage rule refers at intake). Opens the SIU case with it. */
    public Claim referToSiu(Long claimId, String ifMatch, String reason, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.REFER_TO_SIU, user);
        ClaimTransition transition = claim.referToSiu(clock.instant());
        // parent first (BUG-003): a lost race is a clean 409 on the claim, not the one-open-case index
        claims.flush();
        siu.openCase(claim.getId(), SiuReferralPort.Source.MANUAL, reason, user.id(), user.username(),
                claim.getFraudScore());
        auditTrail.transition(claim, transition, actor(user), reason);
        return flushed(claim);
    }

    /**
     * SIU_REVIEW -> OPEN when an investigator records the outcome. Called inside the SIU module's
     * transaction; the investigator is the actor of record.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim completeSiuReview(Long claimId, Long investigatorId, String investigatorName, String outcome) {
        Claim claim = claims.findById(claimId).orElseThrow();
        ClaimTransition transition = claim.siuReviewCompleted(clock.instant());
        auditTrail.transition(claim, transition, new AuditActor(investigatorId, investigatorName), "SIU outcome: " + outcome);
        return flushed(claim);
    }

    public Claim withdraw(Long claimId, String ifMatch, String reason, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.WITHDRAW, user);
        Instant now = clock.instant();
        ClaimFinancialsPort.Position money = financials.position(claim.getId());
        if (money.pendingItems() > 0) {
            throw new BusinessRuleException("PENDING_FINANCIALS",
                    "A payment or approval is in progress; the adjuster must resolve it first");
        }
        ClaimTransition transition = claim.withdraw(money.anyPaymentIssued(), now);
        financials.releaseOpenExposures(claim.getId(), user.id(), user.username(), "claim withdrawn");
        infoRequests.findByClaimIdAndStatus(claim.getId(), InfoRequest.Status.OPEN).ifPresent(r -> {
            r.cancel(now);
            timers.cancel(r);
        });
        auditTrail.transition(claim, transition, actor(user), reason);
        return flushed(claim);
    }

    /** Closing needs every exposure closed and nothing in progress; PAID if any money went out. */
    public Claim close(Long claimId, String ifMatch, String reason, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.CLOSE, user);
        ClaimFinancialsPort.Position money = financials.position(claim.getId());
        if (money.openExposures() > 0) {
            throw new BusinessRuleException("OPEN_EXPOSURES", "Close the claim's " + money.openExposures()
                    + " open exposure(s) first");
        }
        if (money.pendingItems() > 0) {
            throw new BusinessRuleException("PENDING_FINANCIALS",
                    "Payments or approvals are still in progress on this claim");
        }
        ClaimTransition transition = claim.close(money.anyPaymentIssued(), clock.instant());
        auditTrail.transition(claim, transition, actor(user), reason);
        return flushed(claim);
    }

    /**
     * A supervisor approved a denial (maker-checker, in the financials module). Called inside that
     * approval's transaction; the approver is the actor of record.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Claim applyApprovedDenial(Long claimId, Long approverId, String approverName, String reason) {
        Claim claim = claims.findById(claimId).orElseThrow();
        ClaimTransition transition = claim.deny(clock.instant());
        auditTrail.transition(claim, transition, new AuditActor(approverId, approverName), reason);
        return flushed(claim);
    }

    public Claim reopen(Long claimId, String ifMatch, String reason, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.REOPEN, user);
        ClaimTransition transition = claim.reopen(clock.instant());
        auditTrail.transition(claim, transition, actor(user), reason);
        return flushed(claim);
    }

    public Claim reassign(Long claimId, String ifMatch, Long adjusterId, String reason, CurrentUser user) {
        Claim claim = load(claimId, ifMatch, ClaimAction.REASSIGN, user);
        UserRef target = users.ref(adjusterId);
        if (target.role() != Role.ADJUSTER) {
            throw new BusinessRuleException("NOT_AN_ADJUSTER", target.username() + " is not an adjuster");
        }
        if (claim.isAssignedTo(adjusterId)) {
            throw new BusinessRuleException("ALREADY_ASSIGNED", "The claim is already assigned to " + target.username());
        }
        Long previous = claim.assignTo(adjusterId, clock.instant());
        Map<String, Object> before = new HashMap<>();
        before.put("adjusterId", previous);
        auditTrail.assigned(claim, previous, actor(user), before, reason);
        return flushed(claim);
    }

    /** Notes don't change the claim, so no If-Match: two people can add notes at the same time. */
    public ClaimNote addNote(Long claimId, String body, CurrentUser user) {
        Claim claim = access.loadVisible(claimId, user);
        access.requirePermission(ClaimAction.ADD_NOTE, claim, user);
        return notes.save(new ClaimNote(claim.getId(), user.id(), body, clock.instant()));
    }

    private Claim load(Long claimId, String ifMatch, ClaimAction action, CurrentUser user) {
        Claim claim = access.loadVisible(claimId, user);
        access.requirePermission(action, claim, user);
        ETags.requireMatch(ifMatch, claim.getVersion());
        return claim;
    }

    private InfoRequest openRequest(Claim claim) {
        return infoRequests.findByClaimIdAndStatus(claim.getId(), InfoRequest.Status.OPEN)
                .orElseThrow(() -> new IllegalStateException("Claim " + claim.getId() + " awaits info but has no open request"));
    }

    private Claim flushed(Claim claim) {
        claims.flush();   // the UPDATE runs now, so version (the ETag) is the new one
        return claim;
    }

    private static AuditActor actor(CurrentUser user) {
        return ClaimAuditTrail.actor(user);
    }
}
