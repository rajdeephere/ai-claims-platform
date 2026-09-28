package com.claimsai.identity.app;

import com.claimsai.identity.domain.Role;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated caller, taken from the verified access token. */
public record CurrentUser(Long id, String username, Role role) {

    public static CurrentUser from(Jwt jwt) {
        Number id = jwt.getClaim(TokenService.CLAIM_USER_ID);
        return new CurrentUser(id.longValue(), jwt.getSubject(), Role.valueOf(jwt.getClaimAsString(TokenService.CLAIM_ROLE)));
    }

    public boolean hasRole(Role expected) {
        return role == expected;
    }
}
