package com.claimsai.financials.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.common.web.ETags;
import com.claimsai.financials.api.FinancialsDtos.ApprovalResponse;
import com.claimsai.financials.api.FinancialsDtos.CreateExposureRequest;
import com.claimsai.financials.api.FinancialsDtos.ExposureResponse;
import com.claimsai.financials.api.FinancialsDtos.PaymentRequest;
import com.claimsai.financials.api.FinancialsDtos.PaymentResponse;
import com.claimsai.financials.api.FinancialsDtos.ReasonRequest;
import com.claimsai.financials.api.FinancialsDtos.RecoveryRequest;
import com.claimsai.financials.api.FinancialsDtos.RecoveryResponse;
import com.claimsai.financials.api.FinancialsDtos.ReserveRequest;
import com.claimsai.financials.api.FinancialsDtos.ReserveResponse;
import com.claimsai.financials.app.FinancialsService;
import com.claimsai.financials.app.FinancialsService.ExposureView;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.app.CurrentUserProvider;
import com.claimsai.platform.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** Exposures, reserves, payments, denial requests and recoveries: staff only. */
@RestController
@PreAuthorize("hasAnyRole('ADJUSTER', 'SUPERVISOR', 'SIU')")
@Tag(name = "Financials", description = "Exposures, reserves, payments, recoveries")
public class FinancialsController {

    private final FinancialsService financials;
    private final CurrentUserProvider currentUser;

    public FinancialsController(FinancialsService financials, CurrentUserProvider currentUser) {
        this.financials = financials;
        this.currentUser = currentUser;
    }

    @PostMapping("/api/v1/claims/{claimId}/exposures")
    @Operation(operationId = "createExposure", summary = "Create an exposure, with an optional initial reserve")
    @ApiResponse(responseCode = "201", description = "Created; pendingApproval set if the reserve is above your authority")
    @DocumentedErrors({404, 409, 422})
    public ResponseEntity<ReserveResponse> createExposure(@PathVariable Long claimId,
                                                          @Valid @RequestBody CreateExposureRequest request) {
        CurrentUser user = currentUser.get();
        var outcome = financials.createExposure(claimId, request.type(), request.claimantName(), request.reserve(),
                request.reason(), user);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(ETags.of(outcome.exposure().getVersion()))
                .body(reserveResponse(outcome));
    }

    @GetMapping("/api/v1/claims/{claimId}/exposures")
    @Operation(operationId = "listExposures", summary = "Exposures with reserve, paid, committed and available amounts")
    @DocumentedErrors({404})
    public List<ExposureResponse> exposures(@PathVariable Long claimId) {
        return financials.exposures(claimId, currentUser.get()).stream().map(ExposureResponse::of).toList();
    }

    @PutMapping("/api/v1/exposures/{id}/reserve")
    @Operation(operationId = "changeReserve", summary = "Set the reserve (If-Match: the exposure's ETag)",
            description = "Lowering is applied at once; raising above your authority creates an approval request.")
    @DocumentedErrors({404, 409, 412, 422, 428})
    public ResponseEntity<ReserveResponse> changeReserve(@PathVariable Long id,
                                                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                         @Valid @RequestBody ReserveRequest request) {
        var outcome = financials.changeReserve(id, ifMatch, request.amount(), request.reason(), currentUser.get());
        return ResponseEntity.ok().body(reserveResponse(outcome));
    }

    @PostMapping("/api/v1/exposures/{id}/close")
    @Operation(operationId = "closeExposure", summary = "Close an exposure, releasing its unused reserve")
    @DocumentedErrors({404, 409, 412, 422, 428})
    public ExposureResponse closeExposure(@PathVariable Long id,
                                          @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                          @Valid @RequestBody ReasonRequest request) {
        Exposure e = financials.closeExposure(id, ifMatch, request.reason(), currentUser.get());
        return ExposureResponse.of(new ExposureView(e, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @PostMapping("/api/v1/exposures/{id}/payments")
    @Operation(operationId = "requestPayment", summary = "Request a payment (Idempotency-Key required)",
            description = "Within your authority: APPROVED and sent to the payment rail. Above it: PENDING_APPROVAL "
                    + "until a supervisor approves. Never more than the available reserve; never under SIU review.")
    @ApiResponse(responseCode = "201", description = "Payment created (or replayed)")
    @DocumentedErrors({404, 409, 422})
    public ResponseEntity<PaymentResponse> requestPayment(
            @PathVariable Long id,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        var outcome = financials.requestPayment(id, request.amount(), request.payeeName(), request.reason(),
                currentUser.get(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(PaymentResponse.of(outcome.payment()));
    }

    @GetMapping("/api/v1/claims/{claimId}/payments")
    @Operation(operationId = "listPayments", summary = "Payments on the claim")
    @DocumentedErrors({404})
    public List<PaymentResponse> payments(@PathVariable Long claimId) {
        return financials.payments(claimId, currentUser.get()).stream().map(PaymentResponse::of).toList();
    }

    @PostMapping("/api/v1/claims/{claimId}/denial-requests")
    @Operation(operationId = "requestDenial", summary = "Propose denying the claim; a supervisor decides")
    @ApiResponse(responseCode = "201", description = "Denial request created")
    @DocumentedErrors({404, 409})
    public ResponseEntity<ApprovalResponse> requestDenial(@PathVariable Long claimId,
                                                          @Valid @RequestBody ReasonRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApprovalResponse.of(financials.requestDenial(claimId, request.reason(), currentUser.get())));
    }

    @PostMapping("/api/v1/claims/{claimId}/recoveries")
    @Operation(operationId = "recordRecovery", summary = "Record money recovered (subrogation, salvage)")
    @ApiResponse(responseCode = "201", description = "Recovery recorded")
    @DocumentedErrors({404, 409, 422})
    public ResponseEntity<RecoveryResponse> recordRecovery(@PathVariable Long claimId,
                                                           @Valid @RequestBody RecoveryRequest request) {
        var recovery = financials.recordRecovery(claimId, request.amount(), request.source(), request.reference(),
                request.receivedOn(), currentUser.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecoveryResponse.of(recovery));
    }

    @GetMapping("/api/v1/claims/{claimId}/recoveries")
    @Operation(operationId = "listRecoveries", summary = "Recoveries on the claim")
    @DocumentedErrors({404})
    public List<RecoveryResponse> recoveries(@PathVariable Long claimId) {
        return financials.recoveries(claimId, currentUser.get()).stream().map(RecoveryResponse::of).toList();
    }

    private ReserveResponse reserveResponse(FinancialsService.ReserveOutcome outcome) {
        Exposure e = outcome.exposure();
        BigDecimal committed = BigDecimal.ZERO;   // a reserve change never commits payments
        return new ReserveResponse(ExposureResponse.of(new ExposureView(e, committed,
                e.isOpen() ? e.getReserveAmount().subtract(e.getPaidAmount()) : BigDecimal.ZERO)),
                ApprovalResponse.of(outcome.pendingApproval()));
    }
}
