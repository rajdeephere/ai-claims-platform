package com.claimsai.financials.api;

import com.claimsai.financials.app.FinancialsService.ExposureView;
import com.claimsai.financials.domain.ApprovalRequest;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.financials.domain.Payment;
import com.claimsai.financials.domain.Recovery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class FinancialsDtos {

    private FinancialsDtos() {
    }

    // ---------- requests ----------

    public record CreateExposureRequest(
            @NotNull Exposure.Type type,
            @Schema(example = "Asha Verma (insured)") @NotBlank @Size(max = 100) String claimantName,
            @Schema(description = "Initial reserve; above your authority limit it waits for approval", example = "4000.00")
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal reserve,
            @Size(max = 2000) String reason) {
    }

    public record ReserveRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                 @NotBlank @Size(max = 2000) String reason) {
    }

    public record PaymentRequest(
            @Schema(example = "3800.00") @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @Schema(example = "City Motors Pvt Ltd") @NotBlank @Size(max = 100) String payeeName,
            @Size(max = 2000) String reason) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record DecisionRequest(@Size(max = 2000) String comment) {
    }

    public record RecoveryRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
                                  @NotNull Recovery.Source source, @Size(max = 100) String reference,
                                  @NotNull @PastOrPresent LocalDate receivedOn) {
    }

    // ---------- responses ----------

    public record ExposureResponse(Long id, Long claimId, Exposure.Type type, String coverageType, String claimantName,
                                   Exposure.Status status, BigDecimal reserve, BigDecimal paid,
                                   @Schema(description = "Committed to payments awaiting approval or issuing")
                                   BigDecimal committed,
                                   @Schema(description = "What can still be paid") BigDecimal available,
                                   long version, Instant createdAt, Instant closedAt) {

        static ExposureResponse of(ExposureView v) {
            Exposure e = v.exposure();
            return new ExposureResponse(e.getId(), e.getClaimId(), e.getType(), e.getCoverageType(), e.getClaimantName(),
                    e.getStatus(), e.getReserveAmount(), e.getPaidAmount(), v.committed(), v.available(), e.getVersion(),
                    e.getCreatedAt(), e.getClosedAt());
        }
    }

    @Schema(description = "pendingApproval is set when the change is above your authority and waits for a supervisor")
    public record ReserveResponse(ExposureResponse exposure, ApprovalResponse pendingApproval) {
    }

    public record PaymentResponse(Long id, Long claimId, Long exposureId, BigDecimal amount, String payeeName,
                                  Payment.Status status, Long requestedBy, Long approvedBy, Long approvalRequestId,
                                  String externalReference, String failureReason, Instant createdAt,
                                  Instant issuedAt) {

        static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.getId(), p.getClaimId(), p.getExposureId(), p.getAmount(), p.getPayeeName(),
                    p.getStatus(), p.getRequestedBy(), p.getApprovedBy(), p.getApprovalRequestId(),
                    p.getExternalReference(), p.getFailureReason(), p.getCreatedAt(), p.getIssuedAt());
        }
    }

    public record ApprovalResponse(Long id, Long claimId, ApprovalRequest.Kind kind, Long targetId, BigDecimal amount,
                                   String reason, ApprovalRequest.Status status, Long requestedBy, Instant requestedAt,
                                   Long decidedBy, Instant decidedAt, String decisionReason) {

        static ApprovalResponse of(ApprovalRequest r) {
            return r == null ? null : new ApprovalResponse(r.getId(), r.getClaimId(), r.getKind(), r.getTargetId(),
                    r.getAmount(), r.getReason(), r.getStatus(), r.getRequestedBy(), r.getRequestedAt(),
                    r.getDecidedBy(), r.getDecidedAt(), r.getDecisionReason());
        }
    }

    public record RecoveryResponse(Long id, Long claimId, BigDecimal amount, Recovery.Source source, String reference,
                                   LocalDate receivedOn, Long recordedBy, Instant createdAt) {

        static RecoveryResponse of(Recovery r) {
            return new RecoveryResponse(r.getId(), r.getClaimId(), r.getAmount(), r.getSource(), r.getReference(),
                    r.getReceivedOn(), r.getRecordedBy(), r.getCreatedAt());
        }
    }
}
