package com.claimsai.identity.infra;

import com.claimsai.identity.domain.AppUser;
import com.claimsai.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** Uses the unique index on lower(username). */
    Optional<AppUser> findByUsernameIgnoreCase(String username);

    List<AppUser> findByRoleAndActiveTrueOrderByIdAsc(Role role);
}
