package com.claimsai.audit;

import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The audit trail can't be rewritten, not even with direct SQL that bypasses the application. */
class AuditTrailIT extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void auditEventsCanBeInsertedButNeverUpdatedOrDeleted() {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");
        long events = jdbc.sql("SELECT count(*) FROM audit_event WHERE claim_id = ?").param(claimId)
                .query(Long.class).single();
        assertThat(events).isGreaterThanOrEqualTo(5);

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_event SET actor_name = 'someone-else' WHERE claim_id = ?")
                .param(claimId).update())
                .hasMessageContaining("audit_event is append-only: UPDATE is not allowed");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event WHERE claim_id = ?").param(claimId).update())
                .hasMessageContaining("audit_event is append-only: DELETE is not allowed");
    }
}
