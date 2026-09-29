package com.claimsai.activity.api;

import com.claimsai.activity.app.ActivityService.ActivityView;
import com.claimsai.activity.app.SupervisorDashboardService.Dashboard;
import com.claimsai.activity.app.SupervisorDashboardService.OwnerCount;
import com.claimsai.activity.domain.Activity;
import com.claimsai.activity.domain.ActivityType;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.identity.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ActivityDtos {

    private ActivityDtos() {
    }

    public record CompleteRequest(@Schema(example = "Called the claimant; photos promised by Friday")
                                  @Size(max = 2000) String note) {
    }

    public record ActivityResponse(Long id, Long claimId, String claimNumber, ActivityType type, String subject,
                                   @Schema(description = "Set when the activity belongs to one person")
                                   String assignee,
                                   @Schema(description = "Set when the activity is in a role's queue")
                                   Role candidateRole,
                                   Activity.Priority priority, Activity.Status status, Instant dueAt,
                                   @Schema(description = "When the SLA was breached; null if it was not")
                                   Instant escalatedAt,
                                   @Schema(description = "The info request, SIU case or payment it is about")
                                   Long linkedId,
                                   boolean completedByPerson, Instant createdAt, Instant completedAt,
                                   @Schema(description = "null when the system closed it") String completedBy,
                                   String outcomeNote) {

        static ActivityResponse of(ActivityView v) {
            Activity a = v.activity();
            return new ActivityResponse(a.getId(), a.getClaimId(), v.claimNumber(), a.getType(), a.getSubject(),
                    v.assignee() == null ? null : v.assignee().username(), a.getCandidateRole(), a.getPriority(),
                    a.getStatus(), a.getDueAt(), a.getEscalatedAt(), a.getLinkedId(), a.getType().completedByPerson(),
                    a.getCreatedAt(), a.getCompletedAt(), v.completedBy() == null ? null : v.completedBy().username(),
                    a.getOutcomeNote());
        }
    }

    public record OwnerCountResponse(@Schema(description = "A username, or queue:ROLE") String owner, long count) {

        static OwnerCountResponse of(OwnerCount c) {
            return new OwnerCountResponse(c.owner(), c.count());
        }
    }

    public record DashboardResponse(Map<ClaimStatus, Long> claimsByStatus, long unassignedClaims, long pendingApprovals,
                                    long openSiuCases, long openActivities,
                                    @Schema(description = "Open activities past their SLA") long breachedActivities,
                                    List<OwnerCountResponse> breachesByOwner) {

        static DashboardResponse of(Dashboard d) {
            return new DashboardResponse(d.claimsByStatus(), d.unassignedClaims(), d.pendingApprovals(), d.openSiuCases(),
                    d.openActivities(), d.breachedActivities(),
                    d.breachesByOwner().stream().map(OwnerCountResponse::of).toList());
        }
    }
}
