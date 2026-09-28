package com.claimsai.identity.app;

import com.claimsai.identity.config.JwtConfig;
import com.claimsai.identity.domain.AppUser;
import com.claimsai.identity.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final JwtProperties PROPS = new JwtProperties("unit-test-secret-0123456789-0123456789",
            "ai-claims-platform", Duration.ofMinutes(15), Duration.ofDays(7));

    private final JwtConfig config = new JwtConfig(PROPS);
    private final JwtDecoder decoder = config.jwtDecoder();

    private static AppUser user() {
        AppUser user = new AppUser("adjuster1", "hash", "Meera Nair", Role.ADJUSTER, new BigDecimal("5000.00"),
                Instant.now());
        ReflectionTestUtils.setField(user, "id", 42L);
        return user;
    }

    private TokenService serviceAt(Instant now) {
        return new TokenService(config.jwtEncoder(), PROPS, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void accessTokenCarriesIdentityAndRoleButNotTheAuthorityLimit() {
        Instant now = Instant.now();
        TokenService.AccessToken token = serviceAt(now).issueAccessToken(user());

        Jwt jwt = decoder.decode(token.value());
        assertThat(jwt.getSubject()).isEqualTo("adjuster1");
        assertThat(jwt.<Number>getClaim("uid").longValue()).isEqualTo(42L);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADJUSTER");
        assertThat(jwt.getClaims()).doesNotContainKey("authorityLimit");
        assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(CurrentUser.from(jwt)).isEqualTo(new CurrentUser(42L, "adjuster1", Role.ADJUSTER));
    }

    @Test
    void expiredTokenIsRejected() {
        // issued two hours ago: expired even with the decoder's 60 s clock-skew allowance
        String token = serviceAt(Instant.now().minus(Duration.ofHours(2))).issueAccessToken(user()).value();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtProperties other = new JwtProperties("another-secret-0123456789-0123456789", "ai-claims-platform",
                Duration.ofMinutes(15), Duration.ofDays(7));
        String foreign = new TokenService(new JwtConfig(other).jwtEncoder(), other, Clock.systemUTC())
                .issueAccessToken(user()).value();

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        JwtProperties otherIssuer = new JwtProperties(PROPS.secret(), "someone-else", Duration.ofMinutes(15),
                Duration.ofDays(7));
        String token = new TokenService(config.jwtEncoder(), otherIssuer, Clock.systemUTC())
                .issueAccessToken(user()).value();

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void refreshTokenValuesAreRandomAndOnlyTheirHashIsStored() {
        TokenService service = serviceAt(Instant.now());
        String a = service.newRefreshTokenValue();
        String b = service.newRefreshTokenValue();

        assertThat(a).isNotEqualTo(b).hasSize(43);   // 32 bytes, base64url without padding
        assertThat(TokenService.hash(a)).hasSize(64).isEqualTo(TokenService.hash(a)).isNotEqualTo(a);
    }
}
