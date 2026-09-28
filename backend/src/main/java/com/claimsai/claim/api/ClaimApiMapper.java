package com.claimsai.claim.api;

import com.claimsai.claim.api.ClaimDtos.InfoRequestView;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.PortalClaimSummary;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.api.ClaimDtos.StaffClaimSummary;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.claim.api.ClaimDtos.UserView;
import com.claimsai.claim.app.ClaimDetails;
import com.claimsai.claim.app.ClaimDetails.TimelineEntry;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.claim.domain.InfoRequest;
import com.claimsai.identity.app.UserRef;

final class ClaimApiMapper {

    private ClaimApiMapper() {
    }

    static Claim.LossReport toLossReport(ClaimDtos.FnolRequest r) {
        return new Claim.LossReport(r.policyNumber(), r.lossDate(), r.lossType(), r.lossLocation(), r.description(),
                r.injuriesReported(), r.estimatedLoss(), r.contactName(), r.contactPhone());
    }

    static PortalClaimResponse toPortal(ClaimDetails details) {
        Claim c = details.claim();
        return new PortalClaimResponse(c.getId(), c.getClaimNumber(), c.getPolicyNumber(),
                ClaimantStatus.of(c.getStatus(), c.getCloseOutcome()), c.getLossDate(), c.getLossType(),
                c.getLossLocation(), c.getDescription(), c.getEstimatedLoss(), c.getCreatedAt(), c.getClosedAt(),
                infoRequest(details.openInfoRequest()), details.allowedActions());
    }

    static PortalClaimSummary toPortalSummary(Claim c) {
        return new PortalClaimSummary(c.getId(), c.getClaimNumber(), ClaimantStatus.of(c.getStatus(), c.getCloseOutcome()),
                c.getLossType(), c.getLossDate(), c.getCreatedAt(), c.getStatus() == ClaimStatus.AWAITING_INFO);
    }

    static StaffClaimResponse toStaff(ClaimDetails details) {
        Claim c = details.claim();
        return new StaffClaimResponse(c.getId(), c.getClaimNumber(), c.getPolicyNumber(), c.getStatus(),
                c.getCloseOutcome(), c.getSegment(), c.getPolicyVerification(), c.getFlags(), c.getFraudScore(),
                c.getClaimantUserId(), c.getContactName(), c.getContactPhone(), c.getLossDate(), c.getLossType(),
                c.getLossLocation(), c.getDescription(), c.isInjuriesReported(), c.getEstimatedLoss(),
                user(details.assignedAdjuster()), infoRequest(details.openInfoRequest()), c.getCreatedAt(),
                c.getUpdatedAt(), c.getClosedAt(), c.getVersion(), details.allowedActions());
    }

    static StaffClaimSummary toStaffSummary(Claim c, UserRef adjuster) {
        return new StaffClaimSummary(c.getId(), c.getClaimNumber(), c.getStatus(), c.getSegment(), c.getLossType(),
                c.getLossDate(), c.getEstimatedLoss(), c.getFlags(), user(adjuster), c.getCreatedAt());
    }

    static TimelineEntryResponse toTimeline(TimelineEntry e) {
        return new TimelineEntryResponse(e.kind(), e.at(), e.actor(), e.action(), e.before(), e.after(), e.text(),
                e.correlationId());
    }

    private static InfoRequestView infoRequest(InfoRequest r) {
        return r == null ? null : new InfoRequestView(r.getId(), r.getMessage(), r.getRequestedAt());
    }

    private static UserView user(UserRef u) {
        return u == null ? null : new UserView(u.id(), u.username(), u.displayName(), u.role());
    }
}
