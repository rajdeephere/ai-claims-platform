package com.claimsai.identity.api;

import com.claimsai.identity.api.AuthDtos.MeResponse;
import com.claimsai.identity.app.CurrentUserProvider;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.AppUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Auth")
public class MeController {

    private final CurrentUserProvider currentUser;
    private final UserService userService;

    public MeController(CurrentUserProvider currentUser, UserService userService) {
        this.currentUser = currentUser;
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "The logged-in user, with their current authority limit")
    public MeResponse me() {
        // limit from the database, not the token: it may have changed since login
        AppUser user = userService.getActive(currentUser.get().id());
        return new MeResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                user.getAuthorityLimit());
    }
}
