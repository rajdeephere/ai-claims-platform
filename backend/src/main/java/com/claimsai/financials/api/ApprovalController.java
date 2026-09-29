package com.claimsai.financials.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.common.web.PageResponse;
import com.claimsai.financials.api.FinancialsDtos.ApprovalResponse;
import com.claimsai.financials.api.FinancialsDtos.DecisionRequest;
import com.claimsai.financials.api.FinancialsDtos.ReasonRequest;
import com.claimsai.financials.app.ApprovalService;
import com.claimsai.financials.domain.ApprovalRequest;
import com.claimsai.identity.app.CurrentUserProvider;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The supervisor's approval queue: payments and reserves above authority, and every denial. */
@RestController
@RequestMapping("/api/v1/approvals")
@PreAuthorize("hasRole('SUPERVISOR')")
@Tag(name = "Approvals", description = "Maker-checker decisions")
public class ApprovalController {

    private final ApprovalService approvals;
    private final CurrentUserProvider currentUser;

    public ApprovalController(ApprovalService approvals, CurrentUserProvider currentUser) {
        this.approvals = approvals;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(operationId = "listApprovals", summary = "Approval requests by status, oldest first (default PENDING)")
    public PageResponse<ApprovalResponse> queue(@RequestParam(defaultValue = "PENDING") ApprovalRequest.Status status,
                                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(approvals.queue(status, PageRequest.of(page, size,
                Sort.by(Sort.Order.asc("requestedAt"), Sort.Order.asc("id")))), ApprovalResponse::of);
    }

    @PostMapping("/{id}/approve")
    @Operation(operationId = "approveRequest", summary = "Approve (never your own request; needs enough authority)")
    @DocumentedErrors({404, 409, 422})
    public ApprovalResponse approve(@PathVariable Long id, @Valid @RequestBody(required = false) DecisionRequest request) {
        return ApprovalResponse.of(approvals.approve(id, request == null ? null : request.comment(), currentUser.get()));
    }

    @PostMapping("/{id}/reject")
    @Operation(operationId = "rejectRequest", summary = "Reject, with a reason")
    @DocumentedErrors({404, 409, 422})
    public ApprovalResponse reject(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return ApprovalResponse.of(approvals.reject(id, request.reason(), currentUser.get()));
    }
}
