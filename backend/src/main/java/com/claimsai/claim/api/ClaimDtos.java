package com.claimsai.claim.api;

import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.ClaimEnums.ClaimFlag;
import com.claimsai.claim.domain.ClaimEnums.CloseOutcome;
import com.claimsai.claim.domain.ClaimEnums.PolicyVerification;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.ClaimantStatus;
import com.claimsai.claim.domain.LossType;
import com.claimsai.identity.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Request and response bodies. Claimant and staff responses are different types on different paths
 * (ADR-0012): a field added to the staff view can never leak to a claimant by accident.
 */
public final class ClaimDtos {

    private ClaimDtos() {
    }

    // ---------- requests ----------

    public record FnolRequest(
            @Schema(example = "POL-AUTO-1001") @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{3,20}") String policyNumber,
            @Schema(example = "2026-09-20") @NotNull @PastOrPresent LocalDate lossDate,
            @NotNull LossType lossType,
            @Schema(example = "MG Road junction, Bengaluru") @NotBlank @Size(max = 200) String lossLocation,
            @Schema(example = "Rear-ended at a traffic signal; bumper and tail lamp damaged")
            @NotBlank @Size(min = 10, max = 2000) String description,
            @NotNull Boolean injuriesReported,
            @Schema(description = "Reporter's own estimate, optional", example = "3800.00")
            @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal estimatedLoss,
            @Schema(description = "Defaults to the claimant's name; required when staff report for someone")
            @Size(max = 100) String contactName,
            @Pattern(regexp = "[+0-9 ()-]{6,20}") String contactPhone) {
    }

    public record MessageRequest(@NotBlank @Size(max = 2000) String message) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record WithdrawRequest(@Size(max = 2000) String reason) {
    }

    public record ReassignRequest(@NotNull Long adjusterId, @Size(max = 2000) String reason) {
    }

    public record NoteRequest(@NotBlank @Size(max = 4000) String body) {
    }

    // ---------- claimant portal ----------

    public record InfoRequestView(Long id, String message, Instant requestedAt) {
    }

    @Schema(description = "A claim as its claimant sees it: no fraud score, segment, flags or internal notes")
    public record PortalClaimResponse(Long id, String claimNumber, String policyNumber, ClaimantStatus status,
                                      LocalDate lossDate, LossType lossType, String lossLocation,
                                      String description, BigDecimal estimatedLoss, Instant submittedAt,
                                      Instant closedAt, InfoRequestView openInfoRequest,
                                      Set<ClaimAction> allowedActions) {
    }

    public record PortalClaimSummary(Long id, String claimNumber, ClaimantStatus status, LossType lossType,
                                     LocalDate lossDate, Instant submittedAt, boolean actionNeeded) {
    }

    // ---------- staff ----------

    public record UserView(Long id, String username, String displayName, Role role) {
    }

    public record StaffClaimResponse(Long id, String claimNumber, String policyNumber, ClaimStatus status,
                                     CloseOutcome closeOutcome, Segment segment,
                                     PolicyVerification policyVerification, Set<ClaimFlag> flags,
                                     Integer fraudScore, Long claimantUserId, String contactName,
                                     String contactPhone, LocalDate lossDate, LossType lossType,
                                     String lossLocation, String description, boolean injuriesReported,
                                     BigDecimal estimatedLoss, UserView assignedAdjuster,
                                     InfoRequestView openInfoRequest, Instant createdAt, Instant updatedAt,
                                     Instant closedAt,
                                     @Schema(description = "Also sent as the ETag header; send it back in If-Match")
                                     long version,
                                     Set<ClaimAction> allowedActions) {
    }

    public record StaffClaimSummary(Long id, String claimNumber, ClaimStatus status, Segment segment,
                                    LossType lossType, LocalDate lossDate, BigDecimal estimatedLoss,
                                    Set<ClaimFlag> flags, UserView assignedAdjuster, Instant createdAt) {
    }

    public record TimelineEntryResponse(
            @Schema(description = "EVENT (audit trail) or NOTE") String kind,
            Instant at, String actor, String action, Map<String, Object> before, Map<String, Object> after,
            @Schema(description = "Reason for an event, or the note text") String text,
            String correlationId) {
    }

    public record NoteResponse(Long id, Long claimId, String author, String body, Instant createdAt) {
    }
}
