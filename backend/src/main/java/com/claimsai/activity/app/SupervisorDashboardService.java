package com.claimsai.activity.app;

import com.claimsai.activity.domain.Activity;
import com.claimsai.activity.infra.ActivityRepository;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.financials.app.ApprovalService;
import com.claimsai.identity.app.UserRef;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import com.claimsai.siu.app.SiuCaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One screen for the supervisor: queues, what waits for them, and who is behind (design F11). */
@Service
public class SupervisorDashboardService {

    private final ClaimQueryService claims;
    private final ApprovalService approvals;
    private final SiuCaseService siu;
    private final ActivityRepository activities;
    private final UserService users;

    public SupervisorDashboardService(ClaimQueryService claims, ApprovalService approvals, SiuCaseService siu,
                                      ActivityRepository activities, UserService users) {
        this.claims = claims;
        this.approvals = approvals;
        this.siu = siu;
        this.activities = activities;
        this.users = users;
    }

    /** @param owner a username, or "queue:ROLE" for a role's queue */
    public record OwnerCount(String owner, long count) {
    }

    public record Dashboard(Map<ClaimStatus, Long> claimsByStatus, long unassignedClaims, long pendingApprovals,
                            long openSiuCases, long openActivities, long breachedActivities,
                            List<OwnerCount> breachesByOwner) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        List<Object[]> rows = activities.countBreachedByOwner(Activity.Status.OPEN);
        Map<Long, UserRef> people = users.refs(rows.stream().map(r -> (Long) r[0]).filter(Objects::nonNull).toList());
        List<OwnerCount> breaches = rows.stream()
                .map(r -> new OwnerCount(r[0] != null ? people.get((Long) r[0]).username() : "queue:" + (Role) r[1],
                        (Long) r[2]))
                .sorted(Comparator.comparingLong(OwnerCount::count).reversed().thenComparing(OwnerCount::owner))
                .toList();
        return new Dashboard(claims.countByStatus(), claims.countUnassigned(), approvals.pendingCount(), siu.openCount(),
                activities.countByStatus(Activity.Status.OPEN),
                activities.countByStatusAndEscalatedAtIsNotNull(Activity.Status.OPEN), breaches);
    }
}
