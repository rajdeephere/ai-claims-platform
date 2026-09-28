package com.claimsai.identity.api;

import com.claimsai.identity.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/** Request and response bodies of the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @Schema(example = "adjuster1") @NotBlank @Size(max = 50) String username,
            @Schema(example = "Password1!") @NotBlank @Size(max = 100) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 100) String refreshToken) {
    }

    public record TokenResponse(
            String accessToken,
            @Schema(example = "Bearer") String tokenType,
            @Schema(description = "Access token lifetime in seconds", example = "900") long expiresIn,
            Instant accessTokenExpiresAt,
            @Schema(description = "Single-use: every refresh returns a new one and revokes the old one")
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UserSummary user) {
    }

    public record UserSummary(Long id, String username, String displayName, Role role) {
    }

    public record MeResponse(Long id, String username, String displayName, Role role,
                             @Schema(description = "Largest reserve or payment the user may approve alone", example = "5000.00")
                             BigDecimal authorityLimit) {
    }
}
