package com.claimsai.claim.api;

import com.claimsai.claim.api.ClaimDtos.FnolRequest;
import com.claimsai.claim.api.ClaimDtos.MessageRequest;
import com.claimsai.claim.api.ClaimDtos.NoteRequest;
import com.claimsai.claim.api.ClaimDtos.NoteResponse;
import com.claimsai.claim.api.ClaimDtos.ReasonRequest;
import com.claimsai.claim.api.ClaimDtos.ReassignRequest;
import com.claimsai.claim.api.ClaimDtos.StaffClaimResponse;
import com.claimsai.claim.api.ClaimDtos.StaffClaimSummary;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.claim.app.ClaimCommandService;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.claim.app.FnolService;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.claim.domain.ClaimNote;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.common.web.ETags;
import com.claimsai.common.web.PageResponse;
import com.claimsai.common.web.Paging;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.app.CurrentUserProvider;
import com.claimsai.platform.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** Claims for staff: adjusters, supervisors (and SIU from phase 7), in the full internal view. */
@RestController
@RequestMapping("/api/v1/claims")
@PreAuthorize("hasAnyRole('ADJUSTER', 'SUPERVISOR', 'SIU')")
@Tag(name = "Claims", description = "Claim handling for staff")
public class StaffClaimController {

    private static final String IF_MATCH = HttpHeaders.IF_MATCH;

    private final FnolService fnol;
    private final ClaimQueryService queries;
    private final ClaimCommandService commands;
    private final CurrentUserProvider currentUser;

    public StaffClaimController(FnolService fnol, ClaimQueryService queries, ClaimCommandService commands,
                                CurrentUserProvider currentUser) {
        this.fnol = fnol;
        this.queries = queries;
        this.commands = commands;
        this.currentUser = currentUser;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADJUSTER')")
    @Operation(operationId = "reportLossByPhone", summary = "Report a loss taken by phone (FNOL)", description = "contactName is required. Idempotent "
            + "with Idempotency-Key, as in the portal.")
    @ApiResponse(responseCode = "201", description = "Claim created (or replayed); Location and ETag headers set")
    @DocumentedErrors({422})
    public ResponseEntity<StaffClaimResponse> report(
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody FnolRequest request) {
        CurrentUser user = currentUser.get();
        FnolService.FnolResult result = fnol.submit(ClaimApiMapper.toLossReport(request), user, idempotencyKey);
        Claim claim = result.claim();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/claims/" + claim.getId()))
                .eTag(ETags.of(claim.getVersion()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(ClaimApiMapper.toStaff(queries.details(claim, user)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADJUSTER', 'SUPERVISOR')")
    @Operation(operationId = "listClaims", summary = "Work queue, newest first",
            description = "Adjusters always get their own claims (assigneeId is ignored); supervisors see all.")
    public PageResponse<StaffClaimSummary> queue(@RequestParam(required = false) ClaimStatus status,
                                                 @RequestParam(required = false) Segment segment,
                                                 @RequestParam(required = false) Long assigneeId,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = queries.queue(status, segment, assigneeId, currentUser.get(), Paging.newestFirst(page, size));
        return PageResponse.of(result.claims(),
                c -> ClaimApiMapper.toStaffSummary(c, result.adjusters().get(c.getAssignedAdjusterId())));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getClaim", summary = "Claim detail", description = "ETag = version; send it as If-Match on commands")
    @DocumentedErrors({404})
    public ResponseEntity<StaffClaimResponse> claim(@PathVariable Long id) {
        var details = queries.details(id, currentUser.get());
        return ResponseEntity.ok().eTag(ETags.of(details.claim().getVersion())).body(ClaimApiMapper.toStaff(details));
    }

    @GetMapping("/{id}/timeline")
    @Operation(operationId = "getClaimTimeline", summary = "Audit trail and notes, oldest first")
    @DocumentedErrors({404})
    public List<TimelineEntryResponse> timeline(@PathVariable Long id) {
        return queries.timeline(id, currentUser.get()).stream().map(ClaimApiMapper::toTimeline).toList();
    }

    @PostMapping("/{id}/request-info")
    @Operation(operationId = "requestInformation", summary = "Ask the claimant for information", description = "OPEN -> AWAITING_INFO (assigned adjuster)")
    @DocumentedErrors({404, 409, 412, 428})
    public ResponseEntity<StaffClaimResponse> requestInfo(@PathVariable Long id,
                                                          @RequestHeader(name = IF_MATCH, required = false) String ifMatch,
                                                          @Valid @RequestBody MessageRequest request) {
        CurrentUser user = currentUser.get();
        return staff(commands.requestInformation(id, ifMatch, request.message(), user), user);
    }

    @PostMapping("/{id}/close")
    @Operation(operationId = "closeClaim", summary = "Close the claim", description = "OPEN -> CLOSED (assigned adjuster)")
    @DocumentedErrors({404, 409, 412, 428})
    public ResponseEntity<StaffClaimResponse> close(@PathVariable Long id,
                                                    @RequestHeader(name = IF_MATCH, required = false) String ifMatch,
                                                    @Valid @RequestBody ReasonRequest request) {
        CurrentUser user = currentUser.get();
        return staff(commands.close(id, ifMatch, request.reason(), user), user);
    }

    @PostMapping("/{id}/reopen")
    @Operation(operationId = "reopenClaim", summary = "Reopen a closed claim", description = "CLOSED -> OPEN (supervisor)")
    @DocumentedErrors({404, 409, 412, 428})
    public ResponseEntity<StaffClaimResponse> reopen(@PathVariable Long id,
                                                     @RequestHeader(name = IF_MATCH, required = false) String ifMatch,
                                                     @Valid @RequestBody ReasonRequest request) {
        CurrentUser user = currentUser.get();
        return staff(commands.reopen(id, ifMatch, request.reason(), user), user);
    }

    @PostMapping("/{id}/reassign")
    @Operation(operationId = "reassignClaim", summary = "Assign to another adjuster (supervisor)")
    @DocumentedErrors({404, 409, 412, 422, 428})
    public ResponseEntity<StaffClaimResponse> reassign(@PathVariable Long id,
                                                       @RequestHeader(name = IF_MATCH, required = false) String ifMatch,
                                                       @Valid @RequestBody ReassignRequest request) {
        CurrentUser user = currentUser.get();
        return staff(commands.reassign(id, ifMatch, request.adjusterId(), request.reason(), user), user);
    }

    @PostMapping("/{id}/notes")
    @Operation(operationId = "addClaimNote", summary = "Add an internal note (never shown to the claimant)")
    @ApiResponse(responseCode = "201", description = "Note added")
    @DocumentedErrors({404})
    public ResponseEntity<NoteResponse> addNote(@PathVariable Long id, @Valid @RequestBody NoteRequest request) {
        CurrentUser user = currentUser.get();
        ClaimNote note = commands.addNote(id, request.body(), user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new NoteResponse(note.getId(), note.getClaimId(), user.username(), note.getBody(),
                        note.getCreatedAt()));
    }

    private ResponseEntity<StaffClaimResponse> staff(Claim claim, CurrentUser user) {
        return ResponseEntity.ok().eTag(ETags.of(claim.getVersion()))
                .body(ClaimApiMapper.toStaff(queries.details(claim, user)));
    }
}
