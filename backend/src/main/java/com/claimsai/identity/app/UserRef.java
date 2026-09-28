package com.claimsai.identity.app;

import com.claimsai.identity.domain.AppUser;
import com.claimsai.identity.domain.Role;

/** A user as other modules see them: who, not their credentials or limits. */
public record UserRef(Long id, String username, String displayName, Role role) {

    public static UserRef of(AppUser user) {
        return new UserRef(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
