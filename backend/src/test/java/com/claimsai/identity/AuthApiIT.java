package com.claimsai.identity;

import com.claimsai.common.error.ApiError;
import com.claimsai.identity.api.AuthDtos.LoginRequest;
import com.claimsai.identity.api.AuthDtos.MeResponse;
import com.claimsai.identity.api.AuthDtos.RefreshRequest;
import com.claimsai.identity.api.AuthDtos.TokenResponse;
import com.claimsai.identity.domain.Role;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AuthApiIT extends IntegrationTest {

    @Test
    void loginReturnsTokensAndMeShowsRoleAndAuthorityLimitFromTheDatabase() {
        TokenResponse tokens = login("adjuster1");

        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(tokens.expiresIn()).isBetween(890L, 900L);
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.user().role()).isEqualTo(Role.ADJUSTER);

        ResponseEntity<MeResponse> me = http.exchange("/api/v1/me", HttpMethod.GET, bearer(tokens.accessToken()),
                MeResponse.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().username()).isEqualTo("adjuster1");
        assertThat(me.getBody().role()).isEqualTo(Role.ADJUSTER);
        assertThat(me.getBody().authorityLimit()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    void usernameIsCaseInsensitive() {
        assertThat(login("Supervisor1").user().username()).isEqualTo("supervisor1");
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        ResponseEntity<ApiError> wrongPassword = http.postForEntity("/api/v1/auth/login",
                new LoginRequest("adjuster1", "wrong"), ApiError.class);
        ResponseEntity<ApiError> unknownUser = http.postForEntity("/api/v1/auth/login",
                new LoginRequest("nobody", "wrong"), ApiError.class);

        for (ResponseEntity<ApiError> response : java.util.List.of(wrongPassword, unknownUser)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().code()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(response.getBody().message()).isEqualTo("Invalid username or password");
        }
    }

    @Test
    void blankCredentialsAreAValidationError() {
        ResponseEntity<ApiError> response = http.postForEntity("/api/v1/auth/login",
                new LoginRequest("", ""), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().violations()).extracting(ApiError.FieldViolation::field)
                .containsExactlyInAnyOrder("username", "password");
    }

    @Test
    void requestWithoutTokenGets401InTheStandardErrorFormat() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "it-401");
        ResponseEntity<ApiError> response = http.exchange("/api/v1/me", HttpMethod.GET, new HttpEntity<>(headers),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("it-401");
        assertThat(response.getBody().code()).isEqualTo("UNAUTHENTICATED");
        assertThat(response.getBody().correlationId()).isEqualTo("it-401");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/me");
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = login("claimant1").accessToken();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");

        ResponseEntity<ApiError> response = http.exchange("/api/v1/me", HttpMethod.GET, bearer(tampered), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshRotatesTheTokenAndReusingAnOldOneEndsAllSessions() {
        TokenResponse first = login("claimant2");

        ResponseEntity<TokenResponse> rotated = http.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(first.refreshToken()), TokenResponse.class);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = rotated.getBody().refreshToken();
        assertThat(second).isNotEqualTo(first.refreshToken());

        // the first token was already used: someone else has it, so both sessions are ended
        ResponseEntity<ApiError> reuse = http.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(first.refreshToken()), ApiError.class);
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody().code()).isEqualTo("INVALID_REFRESH_TOKEN");

        ResponseEntity<ApiError> legitimateAfterReuse = http.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(second), ApiError.class);
        assertThat(legitimateAfterReuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logoutRevokesTheRefreshTokenAndIsIdempotent() {
        TokenResponse tokens = login("siu1");

        assertThat(http.postForEntity("/api/v1/auth/logout", new RefreshRequest(tokens.refreshToken()), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.postForEntity("/api/v1/auth/logout", new RefreshRequest(tokens.refreshToken()), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<ApiError> refresh = http.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(tokens.refreshToken()), ApiError.class);
        assertThat(refresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void roleChecksReturn403ForTheWrongRole() {
        ResponseEntity<ApiError> adjuster = http.exchange("/test/supervisor-only", HttpMethod.GET,
                bearer(login("adjuster1").accessToken()), ApiError.class);
        ResponseEntity<String> supervisor = http.exchange("/test/supervisor-only", HttpMethod.GET,
                bearer(login("supervisor1").accessToken()), String.class);

        assertThat(adjuster.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adjuster.getBody().code()).isEqualTo("FORBIDDEN");
        assertThat(supervisor.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
