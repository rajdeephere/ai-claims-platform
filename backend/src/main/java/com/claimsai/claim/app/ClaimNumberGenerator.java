package com.claimsai.claim.app;

import com.claimsai.claim.infra.ClaimRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Human-readable claim numbers, e.g. CLM-2026-000042: what a claimant quotes on the phone. The database id
 * stays the technical key; the number comes from a sequence, so it's unique without a lock or a retry.
 */
@Component
public class ClaimNumberGenerator {

    private final ClaimRepository claims;
    private final Clock clock;

    public ClaimNumberGenerator(ClaimRepository claims, Clock clock) {
        this.claims = claims;
        this.clock = clock;
    }

    public String next() {
        return "CLM-%d-%06d".formatted(LocalDate.now(clock).getYear(), claims.nextClaimSequence());
    }
}
