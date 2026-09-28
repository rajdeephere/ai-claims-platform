package com.claimsai.claim.app;

import com.claimsai.claim.domain.TriageRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/** Triage thresholds ({@code app.triage.*}): business settings, changeable without a code change. */
@Validated
@ConfigurationProperties("app.triage")
public record TriageProperties(
        @NotNull BigDecimal fastTrackMaxEstimate,
        @Min(0) @Max(100) int fastTrackMaxFraudScore,
        @NotNull BigDecimal complexMinEstimate,
        @Min(0) @Max(100) int siuReferralScore) {

    public TriageRules.Thresholds thresholds() {
        return new TriageRules.Thresholds(fastTrackMaxEstimate, fastTrackMaxFraudScore, complexMinEstimate,
                siuReferralScore);
    }
}
