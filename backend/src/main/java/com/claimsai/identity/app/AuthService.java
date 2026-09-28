package com.claimsai.identity.app;

import com.claimsai.common.error.AuthenticationFailedException;
import com.claimsai.identity.domain.AppUser;
import com.claimsai.identity.domain.RefreshToken;
import com.claimsai.identity.infra.AppUserRepository;
import com.claimsai.identity.infra.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Login, refresh and logout. Refresh tokens are single-use and rotated; presenting a token that was
 * already used means it was probably stolen, so every session of that user is ended (ADR-0004).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties properties;
    private final Clock clock;
    /** Compared against when the username doesn't exist, so both failures take the same time. */
    private final String dummyHash;

    public AuthService(AppUserRepository users, RefreshTokenRepository refreshTokens, TokenService tokenService,
                       PasswordEncoder passwordEncoder, JwtProperties properties, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    public record IssuedTokens(TokenService.AccessToken accessToken, String refreshToken,
                               Instant refreshTokenExpiresAt, AppUser user) {
    }

    @Transactional
    public IssuedTokens login(String username, String password) {
        AppUser user = users.findByUsernameIgnoreCase(username).orElse(null);
        // Always run BCrypt: returning early for an unknown user would reveal which usernames exist (timing).
        boolean passwordMatches = passwordEncoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordMatches || !user.isActive()) {
            throw invalidCredentials();
        }
        return issue(user);
    }

    /**
     * {@code noRollbackFor}: on token reuse we revoke all sessions AND reject the request. Without it the
     * exception would roll the revocation back, and the stolen session would stay alive.
     */
    @Transactional(noRollbackFor = AuthenticationFailedException.class)
    public IssuedTokens refresh(String rawRefreshToken) {
        RefreshToken token = refreshTokens.findByTokenHash(TokenService.hash(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        Instant now = clock.instant();
        if (token.isRevoked()) {
            int revoked = refreshTokens.revokeAllActiveForUser(token.getUserId(), now);
            log.warn("Refresh token reuse detected for user {}: revoked {} active session(s)",
                    token.getUserId(), revoked);
            throw invalidRefreshToken();
        }
        if (token.isExpired(now)) {
            throw invalidRefreshToken();
        }
        AppUser user = users.findById(token.getUserId())
                .filter(AppUser::isActive)
                .orElseThrow(AuthService::invalidRefreshToken);
        token.revoke(now);
        return issue(user);
    }

    /** Idempotent: an unknown or already revoked token is not an error. */
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(TokenService.hash(rawRefreshToken))
                .ifPresent(token -> token.revoke(clock.instant()));
    }

    private IssuedTokens issue(AppUser user) {
        Instant now = clock.instant();
        String refreshValue = tokenService.newRefreshTokenValue();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshTokens.save(new RefreshToken(user.getId(), TokenService.hash(refreshValue), now, refreshExpiresAt));
        return new IssuedTokens(tokenService.issueAccessToken(user), refreshValue, refreshExpiresAt, user);
    }

    private static AuthenticationFailedException invalidCredentials() {
        return new AuthenticationFailedException("INVALID_CREDENTIALS", "Invalid username or password");
    }

    private static AuthenticationFailedException invalidRefreshToken() {
        return new AuthenticationFailedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }
}
