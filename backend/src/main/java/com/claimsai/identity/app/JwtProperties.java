package com.claimsai.identity.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Token settings ({@code app.security.jwt.*}). The secret has no default outside the local and test
 * profiles, so a deployment without JWT_SECRET fails at startup instead of signing with a known key.
 *
 * @param secret          HMAC-SHA256 key; at least 32 characters (256 bits)
 * @param accessTokenTtl  short-lived bearer token
 * @param refreshTokenTtl single-use token to get a new access token
 */
@Validated
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "must be at least 32 characters (256 bits) for HS256") String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {
}
