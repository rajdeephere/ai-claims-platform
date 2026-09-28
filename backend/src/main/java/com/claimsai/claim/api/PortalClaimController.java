package com.claimsai.claim.api;

import com.claimsai.claim.api.ClaimDtos.FnolRequest;
import com.claimsai.claim.api.ClaimDtos.MessageRequest;
import com.claimsai.claim.api.ClaimDtos.PortalClaimResponse;
import com.claimsai.claim.api.ClaimDtos.PortalClaimSummary;
import com.claimsai.claim.api.ClaimDtos.WithdrawRequest;
import com.claimsai.claim.app.ClaimCommandService;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.claim.app.FnolService;
import com.claimsai.claim.domain.Claim;
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

/** The claimant portal: a claimant's own claims only, in the claimant view. */
@RestController
@RequestMapping("/api/v1/portal/claims")
@PreAuthorize("hasRole('CLAIMANT')")
@Tag(name = "Portal: my claims", description = "Claimant self-service")
public class PortalClaimController {

    private final FnolService fnol;
    private final ClaimQueryService queries;
    private final ClaimCommandService commands;
    private final CurrentUserProvider currentUser;

    public PortalClaimController(FnolService fnol, ClaimQueryService queries, ClaimCommandService commands,
                                 CurrentUserProvider currentUser) {
        this.fnol = fnol;
        this.queries = queries;
        this.commands = commands;
        this.currentUser = currentUser;
    }

    @PostMapping
    @Operation(summary = "Report a loss (FNOL)",
            description = "Send a new Idempotency-Key (e.g. a UUID) per claim and reuse it on retries: a retry returns "
                    + "the claim already created, with the header Idempotent-Replayed: true.")
    @ApiResponse(responseCode = "201", description = "Claim created (or replayed); Location and ETag headers set")
    @DocumentedErrors({422})
    public ResponseEntity<PortalClaimResponse> report(
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody FnolRequest request) {
        CurrentUser user = currentUser.get();
        FnolService.FnolResult result = fnol.submit(ClaimApiMapper.toLossReport(request), user, idempotencyKey);
        Claim claim = result.claim();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/portal/claims/" + claim.getId()))
                .eTag(ETags.of(claim.getVersion()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(ClaimApiMapper.toPortal(queries.details(claim, user)));
    }

    @GetMapping
    @Operation(summary = "My claims, newest first")
    public PageResponse<PortalClaimSummary> myClaims(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(queries.filedBy(currentUser.get(), Paging.newestFirst(page, size)),
                ClaimApiMapper::toPortalSummary);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One of my claims")
    @DocumentedErrors({404})
    public ResponseEntity<PortalClaimResponse> myClaim(@PathVariable Long id) {
        var details = queries.details(id, currentUser.get());
        return ResponseEntity.ok().eTag(ETags.of(details.claim().getVersion())).body(ClaimApiMapper.toPortal(details));
    }

    @PostMapping("/{id}/respond")
    @Operation(summary = "Answer the adjuster's information request", description = "AWAITING_INFO -> OPEN")
    @DocumentedErrors({404, 409, 412, 428})
    public ResponseEntity<PortalClaimResponse> respond(@PathVariable Long id,
                                                       @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                       @Valid @RequestBody MessageRequest request) {
        CurrentUser user = currentUser.get();
        return portal(commands.respond(id, ifMatch, request.message(), user), user);
    }

    @PostMapping("/{id}/withdraw")
    @Operation(summary = "Withdraw my claim", description = "OPEN or AWAITING_INFO -> CLOSED (WITHDRAWN)")
    @DocumentedErrors({404, 409, 412, 422, 428})
    public ResponseEntity<PortalClaimResponse> withdraw(@PathVariable Long id,
                                                        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                        @Valid @RequestBody WithdrawRequest request) {
        CurrentUser user = currentUser.get();
        return portal(commands.withdraw(id, ifMatch, request.reason(), user), user);
    }

    private ResponseEntity<PortalClaimResponse> portal(Claim claim, CurrentUser user) {
        return ResponseEntity.ok().eTag(ETags.of(claim.getVersion()))
                .body(ClaimApiMapper.toPortal(queries.details(claim, user)));
    }
}
