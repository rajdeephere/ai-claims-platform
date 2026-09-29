package com.claimsai.platform;

import com.claimsai.platform.housekeeping.Housekeeping;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HousekeepingIT extends IntegrationTest {

    @Autowired
    private Housekeeping housekeeping;

    private long insertJob(String status, Instant completedAt) {
        return jdbc.sql("""
                        INSERT INTO job (type, payload, status, attempts, max_attempts, due_at, dedup_key,
                                         created_at, updated_at, completed_at)
                        VALUES ('TEST_OK', '{}', :status, 1, 5, :at, :key, :at, :at, :at) RETURNING id""")
                .param("status", status).param("at", Timestamp.from(completedAt))
                .param("key", "hk:" + UUID.randomUUID()).query(Long.class).single();
    }

    private boolean jobExists(long id) {
        return jdbc.sql("SELECT count(*) FROM job WHERE id = ?").param(id).query(Long.class).single() == 1;
    }

    @Test
    void oldTechnicalRowsAreDeletedAndEverythingElseIsKept() {
        Instant old = Instant.now().minus(Duration.ofDays(8));
        long oldDone = insertJob("DONE", old);
        long oldFailed = insertJob("FAILED", old);
        long recentDone = insertJob("DONE", Instant.now());
        Long userId = login("claimant1").user().id();
        String oldKey = "old-" + UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO idempotency_record (idempotency_key, user_id, operation, request_hash, resource_id,
                                                        created_at)
                        VALUES (:key, :user, 'FNOL', 'x', 1, :at)""")
                .param("key", oldKey).param("user", userId).param("at", Timestamp.from(Instant.now().minus(Duration.ofDays(2))))
                .update();

        Housekeeping.Result result = housekeeping.run();

        assertThat(result.jobs()).isGreaterThanOrEqualTo(1);
        assertThat(jobExists(oldDone)).isFalse();
        assertThat(jobExists(oldFailed)).as("failed jobs stay for the ops queue").isTrue();
        assertThat(jobExists(recentDone)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?").param(oldKey)
                .query(Long.class).single()).isZero();
    }
}
