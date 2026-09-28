package com.claimsai.identity.app;

import com.claimsai.common.error.AuthenticationFailedException;
import com.claimsai.identity.domain.AppUser;
import com.claimsai.identity.domain.RefreshToken;
import com.claimsai.identity.domain.Role;
import com.claimsai.identity.infra.AppUserRepository;
import com.claimsai.identity.infra.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final JwtProperties PROPS = new JwtProperties("unit-test-secret-0123456789-0123456789",
            "ai-claims-platform", Duration.ofMinutes(15), Duration.ofDays(7));

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private AuthService service;
    private AppUser adjuster;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        service = new AuthService(users, refreshTokens, tokenService, passwordEncoder, PROPS,
                Clock.fixed(NOW, ZoneOffset.UTC));
        adjuster = new AppUser("adjuster1", "real-hash", "Meera Nair", Role.ADJUSTER, new BigDecimal("5000.00"), NOW);
        ReflectionTestUtils.setField(adjuster, "id", 7L);
        when(tokenService.issueAccessToken(any())).thenReturn(new TokenService.AccessToken("jwt", NOW.plusSeconds(900)));
        when(tokenService.newRefreshTokenValue()).thenReturn("new-refresh");
    }

    @Test
    void unknownUserStillRunsAPasswordCheckSoTimingRevealsNothing() {
        when(users.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost", "x"))
                .isInstanceOf(AuthenticationFailedException.class)
                .extracting("code").isEqualTo("INVALID_CREDENTIALS");
        verify(passwordEncoder).matches("x", "dummy-hash");
    }

    @Test
    void wrongPasswordIsRejected() {
        when(users.findByUsernameIgnoreCase("adjuster1")).thenReturn(Optional.of(adjuster));
        when(passwordEncoder.matches("bad", "real-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login("adjuster1", "bad")).isInstanceOf(AuthenticationFailedException.class);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void inactiveUserCannotLogInEvenWithTheRightPassword() {
        ReflectionTestUtils.setField(adjuster, "active", false);
        when(users.findByUsernameIgnoreCase("adjuster1")).thenReturn(Optional.of(adjuster));
        when(passwordEncoder.matches("good", "real-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login("adjuster1", "good")).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void loginStoresOnlyTheHashOfTheRefreshToken() {
        when(users.findByUsernameIgnoreCase("adjuster1")).thenReturn(Optional.of(adjuster));
        when(passwordEncoder.matches("good", "real-hash")).thenReturn(true);
        when(refreshTokens.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthService.IssuedTokens tokens = service.login("adjuster1", "good");

        assertThat(tokens.refreshToken()).isEqualTo("new-refresh");
        assertThat(tokens.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(refreshTokens).save(org.mockito.ArgumentMatchers.argThat(t ->
                t.getTokenHash().equals(TokenService.hash("new-refresh")) && t.getUserId().equals(7L)));
    }

    @Test
    void refreshRevokesTheUsedTokenAndIssuesANewOne() {
        RefreshToken current = new RefreshToken(7L, TokenService.hash("old"), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(refreshTokens.findByTokenHash(TokenService.hash("old"))).thenReturn(Optional.of(current));
        when(users.findById(7L)).thenReturn(Optional.of(adjuster));

        AuthService.IssuedTokens tokens = service.refresh("old");

        assertThat(current.getRevokedAt()).isEqualTo(NOW);
        assertThat(tokens.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void reusingARevokedTokenRevokesEverySessionOfTheUser() {
        RefreshToken used = new RefreshToken(7L, TokenService.hash("old"), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        used.revoke(NOW.minusSeconds(30));
        when(refreshTokens.findByTokenHash(TokenService.hash("old"))).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.refresh("old"))
                .isInstanceOf(AuthenticationFailedException.class)
                .extracting("code").isEqualTo("INVALID_REFRESH_TOKEN");
        verify(refreshTokens).revokeAllActiveForUser(eq(7L), eq(NOW));
    }

    @Test
    void theRevocationOnReuseIsNotRolledBackByTheRejection() throws Exception {
        Transactional tx = AuthService.class.getMethod("refresh", String.class).getAnnotation(Transactional.class);

        assertThat(tx.noRollbackFor()).contains(AuthenticationFailedException.class);
    }

    @Test
    void expiredTokenIsRejectedWithoutEndingOtherSessions() {
        RefreshToken expired = new RefreshToken(7L, TokenService.hash("old"), NOW.minus(Duration.ofDays(8)), NOW);
        when(refreshTokens.findByTokenHash(TokenService.hash("old"))).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.refresh("old")).isInstanceOf(AuthenticationFailedException.class);
        verify(refreshTokens, never()).revokeAllActiveForUser(any(), any());
    }
}
