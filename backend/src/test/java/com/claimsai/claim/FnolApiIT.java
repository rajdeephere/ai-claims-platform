package com.claimsai.claim;

import com.claimsai.claim.api.ClaimDtos.FnolRequest;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.ClaimEnums.ClaimFlag;
import com.claimsai.claim.domain.ClaimEnums.PolicyVerification;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.claim.domain.LossType;
import com.claimsai.common.error.ApiError;
import com.claimsai.identity.domain.Role;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class FnolApiIT extends IntegrationTest {

    private static final String PORTAL = "/api/v1/portal/claims";

    @Test
    void claimantReportsALossAndGetsAnOpenAssignedClaimInTheClaimantView() {
        ResponseEntity<String> raw = fileClaim("claimant1", PORTAL,
                fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "3800", false), UUID.randomUUID().toString(),
                String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(raw.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(raw.getHeaders().getETag()).matches("\"\\d+\"");
        // internal fields never reach the claimant, not even as nulls
        assertThat(raw.getBody()).doesNotContain("segment", "flags", "fraudScore", "assignedAdjuster",
                "policyVerification");

        ResponseEntity<PortalClaimResponse> created = fileClaim("claimant1", PORTAL,
                fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "3800", false), UUID.randomUUID().toString(),
                PortalClaimResponse.class);
        PortalClaimResponse claim = created.getBody();
        assertThat(created.getHeaders().getLocation()).hasToString(PORTAL + "/" + claim.id());
        assertThat(claim.claimNumber()).matches("CLM-\\d{4}-\\d{6}");
        // FNOL only records the claim; policy check, triage and assignment follow as jobs (ADR-0018)
        assertThat(claim.status()).isEqualTo(ClaimantStatus.RECEIVED);
        assertThat(claim.estimatedLoss()).isEqualByComparingTo("3800.00");
        assertThat(claim.allowedActions()).containsExactly(ClaimAction.UPLOAD_DOCUMENT);   // right after FNOL

        awaitIntake(claim.id());
        assertThat(get("claimant1", PORTAL + "/" + claim.id(), PortalClaimResponse.class).getBody().allowedActions())
                .containsExactlyInAnyOrder(ClaimAction.WITHDRAW, ClaimAction.UPLOAD_DOCUMENT);
        StaffClaimResponse staff = asSupervisor(claim.id());
        assertThat(staff.status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(staff.policyVerification()).isEqualTo(PolicyVerification.VERIFIED);
        assertThat(staff.flags()).isEmpty();
        assertThat(staff.segment()).isEqualTo(Segment.STANDARD);   // 3,800: above the fast-track limit
        assertThat(staff.assignedAdjuster().role()).isEqualTo(Role.ADJUSTER);
        assertThat(staff.contactName()).isEqualTo("Asha Verma");   // defaulted from the claimant's profile
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameClaimInsteadOfADuplicate() {
        String key = UUID.randomUUID().toString();
        FnolRequest request = fnol("POL-HOME-2001", LossType.HOME_WATER, "1500", false);

        ResponseEntity<PortalClaimResponse> first = fileClaim("claimant1", PORTAL, request, key, PortalClaimResponse.class);
        ResponseEntity<PortalClaimResponse> retry = fileClaim("claimant1", PORTAL, request, key, PortalClaimResponse.class);

        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(retry.getBody().claimNumber()).isEqualTo(first.getBody().claimNumber());
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void sameAmountWrittenDifferentlyIsTheSameRequest() {
        String key = UUID.randomUUID().toString();
        Long first = fileClaim("claimant1", PORTAL, fnol("POL-AUTO-1001", LossType.VEHICLE_GLASS, "450", false), key,
                PortalClaimResponse.class).getBody().id();

        ResponseEntity<PortalClaimResponse> retry = fileClaim("claimant1", PORTAL,
                fnol("POL-AUTO-1001", LossType.VEHICLE_GLASS, "450.00", false), key, PortalClaimResponse.class);

        assertThat(retry.getBody().id()).isEqualTo(first);
    }

    @Test
    void reusingAKeyForADifferentClaimIsRefused() {
        String key = UUID.randomUUID().toString();
        fileClaim("claimant1", PORTAL, fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "1000", false), key, String.class);

        ResponseEntity<ApiError> other = fileClaim("claimant1", PORTAL,
                fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "9999", false), key, ApiError.class);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(other.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void keysArePerUserSoTwoUsersCantCollide() {
        String key = UUID.randomUUID().toString();
        Long mine = fileClaim("claimant1", PORTAL, fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "100", false),
                key, PortalClaimResponse.class).getBody().id();
        Long theirs = fileClaim("claimant2", PORTAL, fnol("POL-AUTO-1002", LossType.VEHICLE_COLLISION, "100", false),
                key, PortalClaimResponse.class).getBody().id();

        assertThat(theirs).isNotEqualTo(mine);
    }

    @Test
    void theKeyIsRequired() {
        ResponseEntity<ApiError> response = fileClaim("claimant1", PORTAL,
                fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "100", false), null, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void concurrentRetriesWithOneKeyCreateExactlyOneClaim() throws Exception {
        String key = UUID.randomUUID().toString();
        FnolRequest request = fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "2500", false);
        token("claimant1");   // log in once, before the race

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ResponseEntity<PortalClaimResponse>>> calls = java.util.Collections.nCopies(8,
                    () -> fileClaim("claimant1", PORTAL, request, key, PortalClaimResponse.class));
            List<Long> ids = new java.util.ArrayList<>();
            for (Future<ResponseEntity<PortalClaimResponse>> f : pool.invokeAll(calls)) {
                assertThat(f.get().getStatusCode()).isEqualTo(HttpStatus.CREATED);
                ids.add(f.get().getBody().id());
            }
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLapsedPolicyIsFlaggedAndRoutedToComplexNotDenied() {
        Long id = portalClaim("claimant2", "POL-AUTO-1003");

        StaffClaimResponse claim = asSupervisor(id);
        assertThat(claim.status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(claim.policyVerification()).isEqualTo(PolicyVerification.UNVERIFIED);
        assertThat(claim.flags()).containsExactly(ClaimFlag.POLICY_NOT_IN_FORCE);
        assertThat(claim.segment()).isEqualTo(Segment.COMPLEX);
    }

    @Test
    void claimingOnSomeoneElsesPolicyIsFlagged() {
        Long id = portalClaim("claimant2", "POL-AUTO-1001");

        assertThat(asSupervisor(id).flags()).containsExactly(ClaimFlag.HOLDER_MISMATCH);
    }

    @Test
    void anUnknownPolicyStillCreatesAClaimForReview() {
        Long id = portalClaim("claimant1", "POL-NOPE-0000");

        assertThat(asSupervisor(id).flags()).containsExactly(ClaimFlag.POLICY_NOT_FOUND);
    }

    @Test
    void invalidReportsAreRejectedFieldByField() {
        FnolRequest bad = new FnolRequest("POL-AUTO-1001", LocalDate.now().plusDays(1), LossType.VEHICLE_COLLISION,
                "x", "short", false, new BigDecimal("10.123"), null, null);

        ResponseEntity<ApiError> response = fileClaim("claimant1", PORTAL, bad, UUID.randomUUID().toString(),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().violations()).extracting(ApiError.FieldViolation::field)
                .containsExactlyInAnyOrder("lossDate", "description", "estimatedLoss");
    }

    @Test
    void staffTakeAPhoneFnolForSomeoneWithoutALoginAndMustGiveAContactName() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<ApiError> noName = fileClaim("adjuster2", "/api/v1/claims",
                fnol("POL-AUTO-1002", LossType.VEHICLE_COLLISION, "5000", false), key, ApiError.class);
        assertThat(noName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noName.getBody().code()).isEqualTo("CONTACT_NAME_REQUIRED");

        FnolRequest withName = new FnolRequest("POL-AUTO-1002", LocalDate.now().minusDays(1),
                LossType.VEHICLE_COLLISION, "Ring Road", "Hit a divider in heavy rain", false, null, "Rohan Iyer",
                "+91 90000 00000");
        ResponseEntity<StaffClaimResponse> created = fileClaim("adjuster2", "/api/v1/claims", withName,
                UUID.randomUUID().toString(), StaffClaimResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().claimantUserId()).isNull();
        assertThat(created.getBody().contactName()).isEqualTo("Rohan Iyer");
        // the adjuster who took the call can see it even if it was assigned to a colleague
        assertThat(get("adjuster2", "/api/v1/claims/" + created.getBody().id(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void eachRoleUsesItsOwnEntryPoint() {
        FnolRequest request = fnol("POL-AUTO-1001", LossType.VEHICLE_COLLISION, "100", false);

        assertThat(fileClaim("claimant1", "/api/v1/claims", request, UUID.randomUUID().toString(), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fileClaim("adjuster1", PORTAL, request, UUID.randomUUID().toString(), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fileClaim("supervisor1", "/api/v1/claims", request, UUID.randomUUID().toString(), ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
