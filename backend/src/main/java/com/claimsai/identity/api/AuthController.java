package com.claimsai.identity.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.identity.api.AuthDtos.LoginRequest;
import com.claimsai.identity.api.AuthDtos.RefreshRequest;
import com.claimsai.identity.api.AuthDtos.TokenResponse;
import com.claimsai.identity.api.AuthDtos.UserSummary;
import com.claimsai.common.error.AuthenticationFailedException;
import com.claimsai.identity.app.AuthService;
import com.claimsai.identity.app.LoginAttemptLimiter;
import com.claimsai.identity.app.AuthService.IssuedTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Login and token refresh")
@SecurityRequirements   // public: no bearer token needed
public class AuthController {

    private final AuthService authService;
    private final LoginAttemptLimiter loginLimiter;
    private final Clock clock;

    public AuthController(AuthService authService, LoginAttemptLimiter loginLimiter, Clock clock) {
        this.authService = authService;
        this.loginLimiter = loginLimiter;
        this.clock = clock;
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Log in", description = "Demo users: claimant1, claimant2, adjuster1, adjuster2, "
            + "supervisor1, siu1 (password Password1!)")
    @DocumentedErrors({401, 429})
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = http.getRemoteAddr();   // behind Render's proxy: X-Forwarded-For, via forward-headers-strategy
        loginLimiter.checkAllowed(request.username(), ip);
        try {
            return toResponse(authService.login(request.username(), request.password()));
        } catch (AuthenticationFailedException e) {
            loginLimiter.recordFailure(request.username(), ip);
            throw e;
        }
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshTokens", summary = "Exchange a refresh token for new tokens",
            description = "The refresh token is single-use. Reusing an old one ends all of the user's sessions.")
    @DocumentedErrors(401)
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return toResponse(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @Operation(operationId = "logout", summary = "Revoke a refresh token", description = "Always 204, even for an unknown token.")
    // springdoc can't see the status inside ResponseEntity and would document 200 (BUG-002)
    @ApiResponse(responseCode = "204", description = "Revoked (or was already unknown or revoked)")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private TokenResponse toResponse(IssuedTokens tokens) {
        long expiresIn = Duration.between(clock.instant(), tokens.accessToken().expiresAt()).toSeconds();
        var user = tokens.user();
        return new TokenResponse(tokens.accessToken().value(), "Bearer", expiresIn, tokens.accessToken().expiresAt(),
                tokens.refreshToken(), tokens.refreshTokenExpiresAt(),
                new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole()));
    }
}
