package com.claimsai.identity.api;

import com.claimsai.identity.api.AuthDtos.UserSummary;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Who can be picked, e.g. the adjusters a supervisor can reassign a claim to. Names only, no limits. */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('SUPERVISOR')")
@Tag(name = "Auth")
public class UserDirectoryController {

    private final UserService users;

    public UserDirectoryController(UserService users) {
        this.users = users;
    }

    @GetMapping
    @Operation(operationId = "listUsersByRole", summary = "Active users with a role (supervisor)")
    public List<UserSummary> byRole(@RequestParam Role role) {
        return users.activeUsers(role).stream()
                .map(u -> new UserSummary(u.id(), u.username(), u.displayName(), u.role())).toList();
    }
}
