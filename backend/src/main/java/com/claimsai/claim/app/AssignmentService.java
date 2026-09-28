package com.claimsai.claim.app;

import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the active adjuster with the fewest open claims (ties: lowest id, so the result is deterministic).
 * Two FNOLs at the same moment may pick the same adjuster; that's a slightly uneven workload, not an
 * error, so no lock is taken. Skill- or segment-based routing (COMPLEX to senior adjusters) is v2.
 */
@Service
public class AssignmentService {

    private final UserService users;
    private final ClaimRepository claims;

    public AssignmentService(UserService users, ClaimRepository claims) {
        this.users = users;
        this.claims = claims;
    }

    public Optional<Long> leastLoadedAdjuster() {
        List<Long> adjusters = users.activeUserIds(Role.ADJUSTER);
        if (adjusters.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Long> workload = new HashMap<>();
        for (Object[] row : claims.countActiveByAssignee(adjusters, ClaimStatus.CLOSED)) {
            workload.put((Long) row[0], (Long) row[1]);
        }
        return adjusters.stream()
                .min(Comparator.<Long>comparingLong(id -> workload.getOrDefault(id, 0L))
                        .thenComparing(Comparator.naturalOrder()));
    }
}
