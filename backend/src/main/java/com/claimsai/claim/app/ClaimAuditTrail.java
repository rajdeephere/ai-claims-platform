package com.claimsai.claim.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimEvents;
import com.claimsai.claim.domain.ClaimTransition;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.platform.outbox.app.OutboxService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Records what happened to a claim: the audit entry (for people and regulators) and, where other parts of
 * the system care, the outbox event (for listeners). Both in the caller's transaction, so a change, its
 * audit entry and its event always commit together.
 */
@Component
public class ClaimAuditTrail {

    static final String ENTITY = "CLAIM";

    private final AuditService audit;
    private final OutboxService outbox;

    public ClaimAuditTrail(AuditService audit, OutboxService outbox) {
        this.audit = audit;
        this.outbox = outbox;
    }

    public static AuditActor actor(CurrentUser user) {
        return new AuditActor(user.id(), user.username());
    }

    public void submitted(Claim claim, AuditActor actor) {
        audit.record(ENTITY, claim.getId(), claim.getId(), "CLAIM_SUBMITTED", actor, null,
                Map.of("claimNumber", claim.getClaimNumber(), "policyNumber", claim.getPolicyNumber(),
                        "lossType", claim.getLossType().name()), null);
        outbox.append(ClaimEvents.AGGREGATE, claim.getId(), ClaimEvents.CLAIM_SUBMITTED, base(claim));
    }

    public void transition(Claim claim, ClaimTransition transition, AuditActor actor, String reason) {
        Map<String, Object> after = new HashMap<>();
        after.put("status", transition.to().name());
        if (claim.getCloseOutcome() != null) {
            after.put("outcome", claim.getCloseOutcome().name());
        }
        audit.record(ENTITY, claim.getId(), claim.getId(), "STATUS_CHANGED", actor,
                Map.of("status", transition.from().name()), after, reason);

        Map<String, Object> event = base(claim);
        event.put(ClaimEvents.FROM, transition.from().name());
        event.put(ClaimEvents.TO, transition.to().name());
        if (claim.getCloseOutcome() != null) {
            event.put(ClaimEvents.OUTCOME, claim.getCloseOutcome().name());
        }
        outbox.append(ClaimEvents.AGGREGATE, claim.getId(), ClaimEvents.CLAIM_STATUS_CHANGED, event);
    }

    public void infoRequested(Claim claim, Long infoRequestId, String message, AuditActor actor) {
        audit.record(ENTITY, claim.getId(), claim.getId(), "INFO_REQUESTED", actor, null,
                Map.of("infoRequestId", infoRequestId), message);
        Map<String, Object> event = base(claim);
        event.put(ClaimEvents.MESSAGE, message);
        outbox.append(ClaimEvents.AGGREGATE, claim.getId(), ClaimEvents.INFO_REQUESTED, event);
    }

    public void event(Claim claim, String action, AuditActor actor, Map<String, Object> before,
                      Map<String, Object> after, String reason) {
        audit.record(ENTITY, claim.getId(), claim.getId(), action, actor, before, after, reason);
    }

    private static Map<String, Object> base(Claim claim) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(ClaimEvents.CLAIM_ID, claim.getId());
        payload.put(ClaimEvents.CLAIM_NUMBER, claim.getClaimNumber());
        if (claim.getClaimantUserId() != null) {
            payload.put(ClaimEvents.CLAIMANT_USER_ID, claim.getClaimantUserId());
        }
        return payload;
    }
}
