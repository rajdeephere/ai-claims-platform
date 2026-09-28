package com.claimsai.claim;

import com.claimsai.claim.api.ClaimDtos.PortalClaimSummary;
import com.claimsai.claim.api.ClaimDtos.StaffClaimSummary;
import com.claimsai.common.error.ApiError;
import com.claimsai.common.web.PageResponse;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimVisibilityIT extends IntegrationTest {

    @Test
    void anotherClaimantsClaimLooksExactlyLikeOneThatDoesNotExist() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");

        ResponseEntity<ApiError> someoneElses = get("claimant2", "/api/v1/portal/claims/" + id, ApiError.class);
        ResponseEntity<ApiError> missing = get("claimant2", "/api/v1/portal/claims/99999999", ApiError.class);

        assertThat(someoneElses.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(someoneElses.getBody().code()).isEqualTo(missing.getBody().code()).isEqualTo("CLAIM_NOT_FOUND");
    }

    @Test
    void anAdjusterOnlySeesClaimsOnTheirDesk() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String other = otherAdjuster(assignedAdjuster(id));

        assertThat(get(other, "/api/v1/claims/" + id, ApiError.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("siu1", "/api/v1/claims/" + id, ApiError.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("supervisor1", "/api/v1/claims/" + id, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void claimantsCantUseTheStaffApi() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");

        assertThat(get("claimant1", "/api/v1/claims/" + id, ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theAdjusterQueueIsAlwaysTheirOwnWhateverFilterTheySend() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(id);
        Long otherId = login(otherAdjuster(adjuster)).user().id();

        PageResponse<StaffClaimSummary> page = http.exchange("/api/v1/claims?size=100&assigneeId=" + otherId,
                HttpMethod.GET, new HttpEntity<>(headers(adjuster)),
                new ParameterizedTypeReference<PageResponse<StaffClaimSummary>>() { }).getBody();

        assertThat(page.content()).isNotEmpty()
                .allSatisfy(c -> assertThat(c.assignedAdjuster().username()).isEqualTo(adjuster));
        assertThat(page.content()).extracting(StaffClaimSummary::id).contains(id);
    }

    @Test
    void supervisorFiltersTheWholeQueue() {
        portalClaim("claimant1", "POL-AUTO-1001");

        PageResponse<StaffClaimSummary> open = http.exchange("/api/v1/claims?status=OPEN&size=5", HttpMethod.GET,
                new HttpEntity<>(headers("supervisor1")),
                new ParameterizedTypeReference<PageResponse<StaffClaimSummary>>() { }).getBody();

        assertThat(open.size()).isEqualTo(5);
        assertThat(open.content()).isNotEmpty().allSatisfy(c -> assertThat(c.status().name()).isEqualTo("OPEN"));
        assertThat(get("siu1", "/api/v1/claims", ApiError.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theClaimantListHasOnlyTheirClaimsNewestFirst() {
        Long older = portalClaim("claimant2", "POL-AUTO-1002");
        Long newer = portalClaim("claimant2", "POL-AUTO-1002");

        PageResponse<PortalClaimSummary> mine = http.exchange("/api/v1/portal/claims?size=100", HttpMethod.GET,
                new HttpEntity<>(headers("claimant2")),
                new ParameterizedTypeReference<PageResponse<PortalClaimSummary>>() { }).getBody();

        assertThat(mine.content()).extracting(PortalClaimSummary::id).containsSubsequence(newer, older);
        Long claimant1Claim = portalClaim("claimant1", "POL-AUTO-1001");
        assertThat(mine.content()).extracting(PortalClaimSummary::id).doesNotContain(claimant1Claim);
    }

    @Test
    void pageSizeIsBounded() {
        ResponseEntity<ApiError> response = get("supervisor1", "/api/v1/claims?size=1000", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().violations()).extracting(ApiError.FieldViolation::field).containsExactly("size");
    }
}
