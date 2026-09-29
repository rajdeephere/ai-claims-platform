package com.claimsai.activity.domain;

import com.claimsai.common.error.ConflictException;
import com.claimsai.identity.domain.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivityTest {

    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    static final Long ADJUSTER = 3L;

    static Activity firstContact() {
        return Activity.open(1L, ActivityType.FIRST_CONTACT, "Contact the claimant", ADJUSTER, null,
                NOW.plus(ActivityType.FIRST_CONTACT.sla()), null, NOW);
    }

    @Test
    void anActivityBelongsToAPersonOrToARoleNeverBoth() {
        assertThatThrownBy(() -> Activity.open(1L, ActivityType.INFO_OVERDUE, "x", ADJUSTER, Role.SUPERVISOR, NOW, null,
                NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Activity.open(1L, ActivityType.INFO_OVERDUE, "x", null, null, NOW, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);

        Activity queued = Activity.open(1L, ActivityType.INFO_OVERDUE, "x", null, Role.SUPERVISOR, NOW, 7L, NOW);
        assertThat(queued.isOwnedBy(5L, Role.SUPERVISOR)).isTrue();
        assertThat(queued.isOwnedBy(ADJUSTER, Role.ADJUSTER)).isFalse();
        assertThat(firstContact().isOwnedBy(ADJUSTER, Role.ADJUSTER)).isTrue();
    }

    @Test
    void aBreachedSlaEscalatesOnceAndRaisesThePriority() {
        Activity a = firstContact();
        assertThat(a.getPriority()).isEqualTo(Activity.Priority.NORMAL);

        assertThat(a.escalate(NOW.plusSeconds(90000))).isTrue();
        assertThat(a.escalate(NOW.plusSeconds(99999))).isFalse();

        assertThat(a.getPriority()).isEqualTo(Activity.Priority.URGENT);
        assertThat(a.getEscalatedAt()).isEqualTo(NOW.plusSeconds(90000));
    }

    @Test
    void aClosedActivityIsNeverEscalatedOrClosedAgain() {
        Activity a = firstContact();
        a.complete(ADJUSTER, "called, photos promised", NOW);

        assertThat(a.escalate(NOW)).isFalse();
        assertThat(a.getCompletedBy()).isEqualTo(ADJUSTER);
        assertThatThrownBy(() -> a.cancel("claim closed", NOW))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("ACTIVITY_NOT_OPEN");
    }

    @Test
    void theSystemClosesWithoutAPerson() {
        Activity a = firstContact();

        a.cancel("claim closed", NOW);

        assertThat(a.getStatus()).isEqualTo(Activity.Status.CANCELLED);
        assertThat(a.getCompletedBy()).isNull();
        assertThat(a.getOutcomeNote()).isEqualTo("claim closed");
    }

    @Test
    void reassigningMovesTheWorkToThePerson() {
        Activity queued = Activity.open(1L, ActivityType.INFO_NO_RESPONSE, "x", null, Role.SUPERVISOR, NOW, 7L, NOW);

        queued.assignTo(4L);

        assertThat(queued.getAssigneeId()).isEqualTo(4L);
        assertThat(queued.getCandidateRole()).isNull();
    }

    @Test
    void onlyTheSiuInvestigationClosesItselfTheRestAreDoneByPeople() {
        for (ActivityType type : ActivityType.values()) {
            assertThat(type.completedByPerson()).as(type.name()).isEqualTo(type != ActivityType.SIU_INVESTIGATION);
        }
    }
}
