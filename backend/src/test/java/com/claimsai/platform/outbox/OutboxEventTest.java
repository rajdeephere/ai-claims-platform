package com.claimsai.platform.outbox;

import com.claimsai.platform.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

    @Test
    void aNewEventIsDueImmediately() {
        OutboxEvent event = new OutboxEvent("CLAIM", 1L, "CLAIM_SUBMITTED", Map.of(), "c-1", NOW);

        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void failuresAreRetriedUntilTheLastAttemptThenParked() {
        OutboxEvent event = new OutboxEvent("CLAIM", 1L, "X", Map.of(), null, NOW);

        event.recordFailure("listener down", 3, NOW.plusSeconds(60), NOW);
        event.recordFailure("listener down", 3, NOW.plusSeconds(120), NOW.plusSeconds(60));
        assertThat(event.getFailedAt()).isNull();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(120));

        event.recordFailure("listener down", 3, NOW.plusSeconds(180), NOW.plusSeconds(120));
        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getFailedAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void publishingClearsTheLastError() {
        OutboxEvent event = new OutboxEvent("CLAIM", 1L, "X", Map.of(), null, NOW);
        event.recordFailure("temporary", 5, NOW.plusSeconds(60), NOW);

        event.markPublished(NOW.plusSeconds(60));

        assertThat(event.getPublishedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(event.getLastError()).isNull();
    }
}
