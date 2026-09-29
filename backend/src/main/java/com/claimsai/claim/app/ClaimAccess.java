package com.claimsai.claim.app;

import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.SiuReferralPort;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.identity.app.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Who may see and act on a claim. Visibility needs data (who filed it, who is assigned), so it can't be a
 * role annotation on the endpoint. Checked in this order:
 * <ol>
 *   <li>not visible: 404, the same as a claim that doesn't exist (no probing of claim ids)</li>
 *   <li>visible, but the role may never do this action: 403</li>
 *   <li>allowed, but not in the current status: 409 from the domain</li>
 * </ol>
 */
@Component
public class ClaimAccess {

    private final ClaimRepository claims;
    private final SiuReferralPort siu;

    public ClaimAccess(ClaimRepository claims, SiuReferralPort siu) {
        this.claims = claims;
        this.siu = siu;
    }

    public Claim loadVisible(Long claimId, CurrentUser user) {
        return claims.findById(claimId)
                .filter(claim -> canSee(claim, user))
                .orElseThrow(() -> new NotFoundException("CLAIM_NOT_FOUND", "Claim " + claimId + " not found"));
    }

    boolean canSee(Claim claim, CurrentUser user) {
        return switch (user.role()) {
            case CLAIMANT -> claim.isFiledBy(user.id());
            // their own claims, and ones they took by phone (assigned to someone else)
            case ADJUSTER -> claim.isAssignedTo(user.id()) || user.id().equals(claim.getReportedByUserId());
            case SUPERVISOR -> true;
            case SIU -> siu.hasCase(claim.getId());   // referred claims only, also after the case is decided
        };
    }

    public void requirePermission(ClaimAction action, Claim claim, CurrentUser user) {
        if (!action.permits(user.role(), claim.isAssignedTo(user.id()), claim.isFiledBy(user.id()))) {
            throw new AccessDeniedException(user.role() + " may not " + action + " on this claim");
        }
    }
}
