package com.claimsai.claim.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimTransition;
import com.claimsai.identity.app.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Claim-specific audit entries, so every service records transitions the same way. */
@Component
public class ClaimAuditTrail {

    static final String ENTITY = "CLAIM";

    private final AuditService audit;

    public ClaimAuditTrail(AuditService audit) {
        this.audit = audit;
    }

    public static AuditActor actor(CurrentUser user) {
        return new AuditActor(user.id(), user.username());
    }

    public void transition(Claim claim, ClaimTransition transition, AuditActor actor, String reason) {
        Map<String, Object> after = new HashMap<>();
        after.put("status", transition.to().name());
        if (claim.getCloseOutcome() != null) {
            after.put("outcome", claim.getCloseOutcome().name());
        }
        audit.record(ENTITY, claim.getId(), claim.getId(), "STATUS_CHANGED", actor,
                Map.of("status", transition.from().name()), after, reason);
    }

    public void event(Claim claim, String action, AuditActor actor, Map<String, Object> before,
                      Map<String, Object> after, String reason) {
        audit.record(ENTITY, claim.getId(), claim.getId(), action, actor, before, after, reason);
    }
}
