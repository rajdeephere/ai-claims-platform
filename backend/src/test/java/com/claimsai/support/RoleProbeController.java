package com.claimsai.support;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint to prove role checks end to end before real role-restricted endpoints exist.
 * Outside /api/v1, so it never appears in the published OpenAPI contract.
 */
@RestController
public class RoleProbeController {

    @GetMapping("/test/supervisor-only")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public String supervisorOnly() {
        return "ok";
    }
}
