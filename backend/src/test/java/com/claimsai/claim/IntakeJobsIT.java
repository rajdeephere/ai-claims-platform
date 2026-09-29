package com.claimsai.claim;

import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.claim.domain.ClaimEnums.ClaimFlag;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.claim.domain.LossType;
import com.claimsai.platform.jobs.domain.Job;
import com.claimsai.platform.jobs.infra.JobRepository;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IntakeJobsIT extends IntegrationTest {

    @Autowired
    private JobRepository jobs;

    @Test
    void fnolAnswersImmediatelyAndTheIntakeJobsFinishTheWorkUnderTheSameCorrelationId() {
        HttpHeaders headers = headers("claimant1");
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.set("X-Correlation-Id", "intake-it-1");
        ResponseEntity<PortalClaimResponse> created = http.exchange("/api/v1/portal/claims", HttpMethod.POST,
                new HttpEntity<>(fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "3800", false), headers),
                PortalClaimResponse.class);
        Long id = created.getBody().id();
        assertThat(created.getBody().status()).isEqualTo(ClaimantStatus.RECEIVED);

        awaitIntake(id);

        Map<String, Job.Status> byType = jobs.findByClaimIdOrderByIdAsc(id).stream()
                .collect(Collectors.toMap(Job::getType, Job::getStatus));
        assertThat(byType).containsEntry("VERIFY_POLICY", Job.Status.DONE)
                .containsEntry("COMPLETE_ASSESSMENT", Job.Status.DONE)
                .containsEntry("ASSESSMENT_TIMEOUT", Job.Status.CANCELLED);   // the assessment finished in time

        List<TimelineEntryResponse> timeline = List.of(get("supervisor1", "/api/v1/claims/" + id + "/timeline",
                TimelineEntryResponse[].class).getBody());
        // work done later, by the system, is still traceable to the claimant's original request
        assertThat(timeline).filteredOn(e -> e.action().equals("POLICY_CHECKED")).singleElement()
                .satisfies(e -> {
                    assertThat(e.actor()).isEqualTo("system");
                    assertThat(e.correlationId()).isEqualTo("intake-it-1");
                });
        assertThat(timeline).extracting(TimelineEntryResponse::correlationId).containsOnly("intake-it-1");
    }

    @Test
    void theTimeoutTimerMovesAStuckAssessmentOnAndFlagsIt() {
        Long claimantId = login("claimant1").user().id();
        // a claim stuck in ASSESSING (in phase 5: the AI never answered), with its timer now due
        Long id = jdbc.sql("""
                        INSERT INTO claim (claim_number, policy_number, claimant_user_id, reported_by_user_id,
                                           contact_name, loss_date, loss_type, loss_location, description,
                                           injuries_reported, status, policy_verification, created_at, updated_at)
                        VALUES (:number, 'POL-AUTO-1001', :user, :user, 'Asha Verma', :lossDate, 'VEHICLE_GLASS',
                                'Home', 'Cracked windscreen', false, 'ASSESSING', 'VERIFIED', now(), now())
                        RETURNING id""")
                .param("number", "CLM-TEST-" + (System.nanoTime() % 1_000_000))
                .param("user", claimantId)
                .param("lossDate", LocalDate.now().minusDays(1))
                .query(Long.class).single();
        jdbc.sql("""
                        INSERT INTO job (type, payload, claim_id, status, attempts, max_attempts, due_at, dedup_key,
                                         created_at, updated_at)
                        VALUES ('ASSESSMENT_TIMEOUT', '{}', :id, 'PENDING', 0, 5, :due, :key, now(), now())""")
                .param("id", id)
                .param("due", Timestamp.from(Instant.now().minusSeconds(1)))
                .param("key", "ASSESSMENT_TIMEOUT:" + id)
                .update();

        awaitIntake(id);

        StaffClaimResponse claim = asSupervisor(id);
        assertThat(claim.status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(claim.flags()).contains(ClaimFlag.ASSESSMENT_TIMED_OUT);
        assertThat(claim.assignedAdjuster()).isNotNull();
    }
}
