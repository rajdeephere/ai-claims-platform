package com.claimsai.identity.app;

import com.claimsai.common.error.NotFoundException;
import com.claimsai.identity.domain.AppUser;
import com.claimsai.identity.domain.Role;
import com.claimsai.identity.infra.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read access to users for the other modules (assignment, authority checks, display names). */
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

    public List<Long> activeUserIds(Role role) {
        return users.findByRoleAndActiveTrueOrderByIdAsc(role).stream().map(AppUser::getId).toList();
    }

    /** Names for display, in one query however many ids (no N+1 when rendering a timeline). */
    public Map<Long, UserRef> refs(Collection<Long> ids) {
        return users.findAllById(ids).stream()
                .map(UserRef::of)
                .collect(Collectors.toMap(UserRef::id, Function.identity()));
    }

    public UserRef ref(Long id) {
        return UserRef.of(getActive(id));
    }
}
