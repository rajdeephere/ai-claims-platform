package com.claimsai.claim.domain;

/** A status change that just happened, returned by the domain so the service can audit (and later publish) it. */
public record ClaimTransition(Long claimId, ClaimStatus from, ClaimStatus to, String action) {
}
