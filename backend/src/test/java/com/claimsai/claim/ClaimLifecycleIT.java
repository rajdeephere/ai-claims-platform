package com.claimsai.claim;

import com.claimsai.claim.api.ClaimDtos.MessageRequest;
import com.claimsai.claim.api.ClaimDtos.NoteRequest;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.ReasonRequest;
import com.claimsai.claim.api.ClaimDtos.ReassignRequest;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.claim.api.ClaimDtos.WithdrawRequest;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.common.error.ApiError;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimLifecycleIT extends IntegrationTest {

    private static String staff(Long id) {
        return "/api/v1/claims/" + id;
    }

    private static String portal(Long id) {
        return "/api/v1/portal/claims/" + id;
    }

    @Test
    void informationRequestRoundTripThenCloseAndReopenWithAFullAuditTrail() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(id);

        // adjuster asks; the claimant sees "action needed" and the question
        HttpHeaders headers = headers(adjuster);
        headers.setIfMatch(etag(adjuster, staff(id)));
        headers.set("X-Correlation-Id", "lifecycle-it-1");
        ResponseEntity<StaffClaimResponse> asked = http.exchange(staff(id) + "/request-info", HttpMethod.POST,
                new HttpEntity<>(new MessageRequest("Please upload the repair estimate"), headers),
                StaffClaimResponse.class);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asked.getBody().status()).isEqualTo(ClaimStatus.AWAITING_INFO);
        assertThat(asked.getHeaders().getETag()).isEqualTo("\"" + asked.getBody().version() + "\"");

        ResponseEntity<PortalClaimResponse> seen = get("claimant1", portal(id), PortalClaimResponse.class);
        assertThat(seen.getBody().status()).isEqualTo(ClaimantStatus.ACTION_NEEDED);
        assertThat(seen.getBody().openInfoRequest().message()).isEqualTo("Please upload the repair estimate");
        assertThat(seen.getBody().allowedActions()).containsExactlyInAnyOrder(ClaimAction.RESPOND, ClaimAction.WITHDRAW,
                ClaimAction.UPLOAD_DOCUMENT);

        // the claimant answers with the ETag of their own view (same version)
        ResponseEntity<PortalClaimResponse> answered = postIfMatch("claimant1", portal(id) + "/respond",
                seen.getHeaders().getETag(), new MessageRequest("Estimate is 3,800 from City Motors"),
                PortalClaimResponse.class);
        assertThat(answered.getBody().status()).isEqualTo(ClaimantStatus.IN_REVIEW);
        assertThat(answered.getBody().openInfoRequest()).isNull();

        ResponseEntity<StaffClaimResponse> closed = postIfMatch(adjuster, staff(id) + "/close",
                etag(adjuster, staff(id)), new ReasonRequest("Repaired by the third party's insurer"),
                StaffClaimResponse.class);
        assertThat(closed.getBody().status()).isEqualTo(ClaimStatus.CLOSED);
        assertThat(closed.getBody().closeOutcome()).isEqualTo(CloseOutcome.NO_PAYMENT);
        assertThat(get("claimant1", portal(id), PortalClaimResponse.class).getBody().status())
                .isEqualTo(ClaimantStatus.CLOSED_NO_PAYMENT);

        ResponseEntity<StaffClaimResponse> reopened = postIfMatch("supervisor1", staff(id) + "/reopen",
                etag("supervisor1", staff(id)), new ReasonRequest("Claimant disputes the other insurer's offer"),
                StaffClaimResponse.class);
        assertThat(reopened.getBody().status()).isEqualTo(ClaimStatus.OPEN);
        assertThat(reopened.getBody().closeOutcome()).isNull();

        List<TimelineEntryResponse> timeline = List.of(get("supervisor1", staff(id) + "/timeline",
                TimelineEntryResponse[].class).getBody());
        assertThat(timeline).extracting(TimelineEntryResponse::action).containsExactly(
                "CLAIM_SUBMITTED", "POLICY_CHECKED", "STATUS_CHANGED", "CLAIM_TRIAGED", "STATUS_CHANGED",
                "CLAIM_ASSIGNED", "INFO_REQUESTED", "STATUS_CHANGED", "INFO_RECEIVED", "STATUS_CHANGED",
                "STATUS_CHANGED", "STATUS_CHANGED");
        assertThat(timeline).filteredOn(e -> e.action().equals("STATUS_CHANGED"))
                .extracting(e -> e.after().get("status"))
                .containsExactly("ASSESSING", "OPEN", "AWAITING_INFO", "OPEN", "CLOSED", "OPEN");
        TimelineEntryResponse request = timeline.get(6);
        assertThat(request.actor()).isEqualTo(adjuster);
        assertThat(request.text()).isEqualTo("Please upload the repair estimate");
        assertThat(request.correlationId()).isEqualTo("lifecycle-it-1");
        assertThat(timeline.get(1).actor()).isEqualTo("system");
    }

    @Test
    void commandsNeedTheCurrentETag() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(id);
        String original = etag(adjuster, staff(id));

        ResponseEntity<ApiError> missing = postIfMatch(adjuster, staff(id) + "/request-info", null,
                new MessageRequest("?"), ApiError.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(missing.getBody().code()).isEqualTo("IF_MATCH_REQUIRED");

        postIfMatch(adjuster, staff(id) + "/request-info", original, new MessageRequest("First question"), String.class);

        // the claimant loaded the page before the adjuster's change
        ResponseEntity<ApiError> stale = postIfMatch("claimant1", portal(id) + "/withdraw", original,
                new WithdrawRequest("changed my mind"), ApiError.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(stale.getBody().code()).isEqualTo("VERSION_MISMATCH");
    }

    @Test
    void anActionTheStatusDoesntAllowIs409AndTheWrongRoleIs403() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(id);

        ResponseEntity<ApiError> notWaiting = postIfMatch("claimant1", portal(id) + "/respond",
                etag("claimant1", portal(id)), new MessageRequest("unasked answer"), ApiError.class);
        assertThat(notWaiting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notWaiting.getBody().code()).isEqualTo("INVALID_TRANSITION");

        ResponseEntity<ApiError> adjusterReopens = postIfMatch(adjuster, staff(id) + "/reopen",
                etag(adjuster, staff(id)), new ReasonRequest("x"), ApiError.class);
        assertThat(adjusterReopens.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<ApiError> supervisorCloses = postIfMatch("supervisor1", staff(id) + "/close",
                etag("supervisor1", staff(id)), new ReasonRequest("x"), ApiError.class);
        assertThat(supervisorCloses.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reassignmentMovesTheClaimToAnotherAdjustersDesk() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String from = assignedAdjuster(id);
        String to = otherAdjuster(from);
        Long toId = login(to).user().id();

        ResponseEntity<StaffClaimResponse> moved = postIfMatch("supervisor1", staff(id) + "/reassign",
                etag("supervisor1", staff(id)), new ReassignRequest(toId, "workload"), StaffClaimResponse.class);

        assertThat(moved.getBody().assignedAdjuster().username()).isEqualTo(to);
        assertThat(get(from, staff(id), ApiError.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(to, staff(id), StaffClaimResponse.class).getBody().allowedActions())
                .contains(ClaimAction.REQUEST_INFO, ClaimAction.CLOSE);
    }

    @Test
    void onlyAdjustersCanBeAssigned() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        Long claimantId = login("claimant2").user().id();

        ResponseEntity<ApiError> response = postIfMatch("supervisor1", staff(id) + "/reassign",
                etag("supervisor1", staff(id)), new ReassignRequest(claimantId, null), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("NOT_AN_ADJUSTER");
    }

    @Test
    void withdrawingWhileWaitingCancelsTheOpenQuestion() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(id);
        postIfMatch(adjuster, staff(id) + "/request-info", etag(adjuster, staff(id)), new MessageRequest("Photos?"),
                String.class);

        ResponseEntity<PortalClaimResponse> withdrawn = postIfMatch("claimant1", portal(id) + "/withdraw",
                etag("claimant1", portal(id)), new WithdrawRequest("sorted it out privately"), PortalClaimResponse.class);

        assertThat(withdrawn.getBody().status()).isEqualTo(ClaimantStatus.WITHDRAWN);
        assertThat(withdrawn.getBody().openInfoRequest()).isNull();
        assertThat(asSupervisor(id).closeOutcome()).isEqualTo(CloseOutcome.WITHDRAWN);
    }

    @Test
    void notesAreInternalAndAppearOnlyInTheStaffTimeline() {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(id);

        assertThat(post(adjuster, staff(id) + "/notes", new NoteRequest("Called the claimant, awaiting photos"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<TimelineEntryResponse> timeline = List.of(get(adjuster, staff(id) + "/timeline",
                TimelineEntryResponse[].class).getBody());
        assertThat(timeline).filteredOn(e -> e.kind().equals("NOTE")).singleElement()
                .satisfies(n -> assertThat(n.text()).isEqualTo("Called the claimant, awaiting photos"));
        assertThat(get("claimant1", portal(id), String.class).getBody()).doesNotContain("awaiting photos");
        assertThat(get("claimant1", staff(id) + "/timeline", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void twoConcurrentCommandsWithTheSameETagLetExactlyOneWin() throws Exception {
        Long id = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(id);
        String etag = etag(adjuster, staff(id));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<ResponseEntity<String>>> both = List.of(
                    () -> postIfMatch(adjuster, staff(id) + "/request-info", etag, new MessageRequest("A?"), String.class),
                    () -> postIfMatch(adjuster, staff(id) + "/request-info", etag, new MessageRequest("B?"), String.class));
            List<HttpStatus> statuses = new java.util.ArrayList<>();
            for (Future<ResponseEntity<String>> f : pool.invokeAll(both)) {
                statuses.add(HttpStatus.valueOf(f.get().getStatusCode().value()));
            }
            // the loser either saw the new version (412) or lost the race at commit (409); never a 500
            assertThat(statuses).containsOnlyOnce(HttpStatus.OK);
            assertThat(statuses).allMatch(s -> s == HttpStatus.OK || s == HttpStatus.CONFLICT
                    || s == HttpStatus.PRECONDITION_FAILED);
        } finally {
            pool.shutdownNow();
        }
    }
}
