package com.claimsai.identity.app;

import com.claimsai.common.error.AuthenticationFailedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Gives services the caller without passing it through every method signature. */
@Component
public class CurrentUserProvider {

    public CurrentUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return CurrentUser.from(jwtAuth.getToken());
        }
        throw new AuthenticationFailedException("UNAUTHENTICATED", "Authentication required");
    }
}
