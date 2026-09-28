package com.claimsai.identity.app;

import com.claimsai.common.error.NotFoundException;
import com.claimsai.identity.domain.AppUser;
import com.claimsai.identity.infra.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to users for the other modules (assignment, authority checks). */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final AppUserRepository users;

    public UserService(AppUserRepository users) {
        this.users = users;
    }

    public AppUser getActive(Long id) {
        return users.findById(id)
                .filter(AppUser::isActive)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User not found"));
    }
}
