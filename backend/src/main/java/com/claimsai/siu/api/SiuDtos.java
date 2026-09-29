package com.claimsai.siu.api;

import com.claimsai.claim.app.ClaimQueryService.PolicyClaim;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.identity.app.UserRef;
import com.claimsai.siu.app.SiuCaseService.CaseFile;
import com.claimsai.siu.app.SiuCaseService.CaseView;
import com.claimsai.siu.domain.SiuCase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SiuDtos {

    private SiuDtos() {
    }

    public record OutcomeRequest(@NotNull SiuCase.Outcome outcome,
                                 @Schema(example = "Invoice reused from claim CLM-2026-000112; garage denies issuing it")
                                 @NotBlank @Size(max = 4000) String findings) {
    }

    public record PersonView(Long id, String username, String displayName) {

        static PersonView of(UserRef u) {
            return u == null ? null : new PersonView(u.id(), u.username(), u.displayName());
        }
    }

    public record SiuCaseResponse(Long id, Long claimId, String claimNumber, SiuCase.Source source, String reason,
                                  @Schema(description = "null when the triage rule referred the claim")
                                  PersonView referredBy, Instant referredAt, Integer fraudScoreAtReferral,
                                  SiuCase.Status status, PersonView investigator, String findings, Instant decidedAt) {

        static SiuCaseResponse of(CaseView v) {
            SiuCase c = v.siuCase();
            return new SiuCaseResponse(c.getId(), c.getClaimId(), v.claimNumber(), c.getSource(), c.getReason(),
                    PersonView.of(v.referredBy()), c.getReferredAt(), c.getFraudScoreAtReferral(), c.getStatus(),
                    PersonView.of(v.investigator()), c.getFindings(), c.getDecidedAt());
        }
    }

    public record ClaimSummary(Long id, String claimNumber, String policyNumber, ClaimStatus status, LocalDate lossDate,
                               String lossType, String description, BigDecimal estimatedLoss, Integer fraudScore) {
    }

    public record OtherClaim(Long id, String claimNumber, LocalDate lossDate, String lossType, ClaimStatus status,
                             Integer fraudScore) {

        static OtherClaim of(PolicyClaim p) {
            return new OtherClaim(p.claimId(), p.claimNumber(), p.lossDate(), p.lossType(), p.status(), p.fraudScore());
        }
    }

    @Schema(description = "Everything an investigator starts from; documents, AI results and the timeline are on the claim")
    public record CaseFileResponse(SiuCaseResponse siuCase, ClaimSummary claim, List<OtherClaim> otherClaimsOnPolicy) {

        static CaseFileResponse of(CaseFile f) {
            var c = f.claim();
            return new CaseFileResponse(SiuCaseResponse.of(f.view()),
                    new ClaimSummary(c.claimId(), c.claimNumber(), c.policyNumber(), c.status(), c.lossDate(),
                            c.lossType(), c.description(), c.estimatedLoss(), f.currentFraudScore()),
                    f.otherClaimsOnPolicy().stream().map(OtherClaim::of).toList());
        }
    }
}
