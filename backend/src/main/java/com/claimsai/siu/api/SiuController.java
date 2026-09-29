package com.claimsai.siu.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.common.web.PageResponse;
import com.claimsai.identity.app.CurrentUserProvider;
import com.claimsai.siu.api.SiuDtos.CaseFileResponse;
import com.claimsai.siu.api.SiuDtos.OutcomeRequest;
import com.claimsai.siu.api.SiuDtos.SiuCaseResponse;
import com.claimsai.siu.app.SiuCaseService;
import com.claimsai.siu.domain.SiuCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The SIU queue and outcomes. Supervisors can watch the queue; only SIU records outcomes. */
@RestController
@Tag(name = "SIU", description = "Fraud investigations")
public class SiuController {

    private final SiuCaseService cases;
    private final CurrentUserProvider currentUser;

    public SiuController(SiuCaseService cases, CurrentUserProvider currentUser) {
        this.cases = cases;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/siu/cases")
    @PreAuthorize("hasAnyRole('SIU', 'SUPERVISOR')")
    @Operation(operationId = "listSiuCases", summary = "SIU cases by status, oldest referral first (default OPEN)")
    public PageResponse<SiuCaseResponse> queue(@RequestParam(defaultValue = "OPEN") SiuCase.Status status,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(cases.queue(status, PageRequest.of(page, size,
                Sort.by(Sort.Order.asc("referredAt"), Sort.Order.asc("id")))), SiuCaseResponse::of);
    }

    @GetMapping("/api/v1/siu/cases/{id}")
    @PreAuthorize("hasAnyRole('SIU', 'SUPERVISOR')")
    @Operation(operationId = "getSiuCase", summary = "The case file: case, claim, other claims on the policy")
    @DocumentedErrors({404})
    public CaseFileResponse caseFile(@PathVariable Long id) {
        return CaseFileResponse.of(cases.caseFile(id));
    }

    @PostMapping("/api/v1/siu/cases/{id}/outcome")
    @PreAuthorize("hasRole('SIU')")
    @Operation(operationId = "recordSiuOutcome", summary = "Record the outcome: CLEARED or CONFIRMED, with findings",
            description = "SIU_REVIEW -> OPEN. CONFIRMED also proposes a denial, which a supervisor decides.")
    @DocumentedErrors({404, 409, 422})
    public SiuCaseResponse recordOutcome(@PathVariable Long id, @Valid @RequestBody OutcomeRequest request) {
        return SiuCaseResponse.of(cases.recordOutcome(id, request.outcome(), request.findings(), currentUser.get()));
    }

    @GetMapping("/api/v1/claims/{claimId}/siu-cases")
    @PreAuthorize("hasAnyRole('ADJUSTER', 'SUPERVISOR', 'SIU')")
    @Operation(operationId = "listClaimSiuCases", summary = "The claim's SIU cases (staff who can see the claim)")
    @DocumentedErrors({404})
    public List<SiuCaseResponse> forClaim(@PathVariable Long claimId) {
        return cases.forClaim(claimId, currentUser.get()).stream().map(SiuCaseResponse::of).toList();
    }
}
