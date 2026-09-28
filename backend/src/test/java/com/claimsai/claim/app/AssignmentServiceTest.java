package com.claimsai.claim.app;

import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssignmentServiceTest {

    private final UserService users = mock(UserService.class);
    private final ClaimRepository claims = mock(ClaimRepository.class);
    private final AssignmentService service = new AssignmentService(users, claims);

    @Test
    void picksTheAdjusterWithTheFewestOpenClaims() {
        when(users.activeUserIds(Role.ADJUSTER)).thenReturn(List.of(3L, 4L));
        when(claims.countActiveByAssignee(any(), eq(ClaimStatus.CLOSED)))
                .thenReturn(List.of(new Object[]{3L, 5L}, new Object[]{4L, 2L}));

        assertThat(service.leastLoadedAdjuster()).contains(4L);
    }

    @Test
    void anAdjusterWithNoClaimsCountsAsZeroAndTiesGoToTheLowestId() {
        when(users.activeUserIds(Role.ADJUSTER)).thenReturn(List.of(3L, 4L, 7L));
        when(claims.countActiveByAssignee(any(), eq(ClaimStatus.CLOSED)))
                .thenReturn(List.<Object[]>of(new Object[]{3L, 1L}));

        assertThat(service.leastLoadedAdjuster()).contains(4L);
    }

    @Test
    void noActiveAdjusterMeansNoAssignment() {
        when(users.activeUserIds(Role.ADJUSTER)).thenReturn(List.of());

        assertThat(service.leastLoadedAdjuster()).isEmpty();
    }
}
