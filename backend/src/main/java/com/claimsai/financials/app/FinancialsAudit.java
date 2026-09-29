package com.claimsai.financials.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.identity.app.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Every money decision on the claim's timeline: who, what, before and after, why. */
@Component
public class FinancialsAudit {

    private final AuditService audit;

    public FinancialsAudit(AuditService audit) {
        this.audit = audit;
    }

    public void record(String entityType, Long entityId, Long claimId, String action, AuditActor actor,
                       Map<String, Object> before, Map<String, Object> after, String reason) {
        audit.record(entityType, entityId, claimId, action, actor, before, after, reason);
    }

    public static AuditActor actor(CurrentUser user) {
        return new AuditActor(user.id(), user.username());
    }
}
