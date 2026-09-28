package com.claimsai.audit.app;

/** Who caused an audited change: a user, or the system (jobs, automatic intake). */
public record AuditActor(Long userId, String name) {

    public static final AuditActor SYSTEM = new AuditActor(null, "system");
}
