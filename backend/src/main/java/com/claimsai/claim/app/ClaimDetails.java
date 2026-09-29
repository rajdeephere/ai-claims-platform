package com.claimsai.claim.app;

import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.InfoRequest;
import com.claimsai.identity.app.UserRef;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** A claim with what the API shows next to it, resolved for one user. */
public record ClaimDetails(Claim claim, UserRef assignedAdjuster, InfoRequest openInfoRequest,
                           Set<ClaimAction> allowedActions, BigDecimal amountPaid) {

    /** One line of the claim timeline: an audit event or a note, oldest first. */
    public record TimelineEntry(String kind, Instant at, String actor, String action, Map<String, Object> before,
                                Map<String, Object> after, String text, String correlationId) {
    }
}
