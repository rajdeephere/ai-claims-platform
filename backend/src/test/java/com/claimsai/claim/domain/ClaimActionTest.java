package com.claimsai.claim.domain;

import com.claimsai.identity.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static com.claimsai.claim.domain.ClaimAction.ADD_NOTE;
import static com.claimsai.claim.domain.ClaimAction.CLOSE;
import static com.claimsai.claim.domain.ClaimAction.REASSIGN;
import static com.claimsai.claim.domain.ClaimAction.REOPEN;
import static com.claimsai.claim.domain.ClaimAction.REQUEST_INFO;
import static com.claimsai.claim.domain.ClaimAction.RESPOND;
import static com.claimsai.claim.domain.ClaimAction.UPLOAD_DOCUMENT;
import static com.claimsai.claim.domain.ClaimAction.WITHDRAW;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimActionTest {

    static final Long CLAIMANT = 1L;
    static final Long ADJUSTER = 3L;
    static final Long OTHER_ADJUSTER = 4L;
    static final Long SUPERVISOR = 5L;

    @Test
    void assignedAdjusterOnAnOpenClaim() {
        Claim claim = ClaimTest.open();   // filed by user 1, assigned to user 3

        assertThat(ClaimAction.allowed(claim, ADJUSTER, Role.ADJUSTER)).containsExactlyInAnyOrder(REQUEST_INFO, CLOSE, ADD_NOTE,
                UPLOAD_DOCUMENT);
    }

    @Test
    void anotherAdjusterMayOnlyAddNotes() {
        assertThat(ClaimAction.allowed(ClaimTest.open(), OTHER_ADJUSTER, Role.ADJUSTER)).containsExactly(ADD_NOTE);
    }

    @Test
    void supervisorReassignsButNeverClosesForTheAdjuster() {
        assertThat(ClaimAction.allowed(ClaimTest.open(), SUPERVISOR, Role.SUPERVISOR))
                .containsExactlyInAnyOrder(REASSIGN, ADD_NOTE, UPLOAD_DOCUMENT);
    }

    @Test
    void claimantActionsFollowTheStatus() {
        Claim claim = ClaimTest.open();
        assertThat(ClaimAction.allowed(claim, CLAIMANT, Role.CLAIMANT)).containsExactlyInAnyOrder(WITHDRAW, UPLOAD_DOCUMENT);

        claim.requestInformation(ClaimTest.NOW);
        assertThat(ClaimAction.allowed(claim, CLAIMANT, Role.CLAIMANT)).containsExactlyInAnyOrder(RESPOND, WITHDRAW,
                UPLOAD_DOCUMENT);

        claim.informationReceived(ClaimTest.NOW);
        claim.close(false, ClaimTest.NOW);
        assertThat(ClaimAction.allowed(claim, CLAIMANT, Role.CLAIMANT)).isEmpty();
    }

    @Test
    void aDifferentClaimantGetsNothing() {
        assertThat(ClaimAction.allowed(ClaimTest.open(), 2L, Role.CLAIMANT)).isEmpty();
    }

    @Test
    void closedClaimOnlyOffersReopenToTheSupervisor() {
        Claim claim = ClaimTest.open();
        claim.close(false, ClaimTest.NOW);

        assertThat(ClaimAction.allowed(claim, SUPERVISOR, Role.SUPERVISOR)).containsExactlyInAnyOrder(REOPEN, ADD_NOTE);
        assertThat(ClaimAction.allowed(claim, ADJUSTER, Role.ADJUSTER)).containsExactly(ADD_NOTE);
    }

    @Test
    void siuCannotChangeAClaimInPhase2() {
        assertThat(ClaimAction.allowed(ClaimTest.open(), 6L, Role.SIU)).isEqualTo(EnumSet.of(ADD_NOTE));
    }
}
