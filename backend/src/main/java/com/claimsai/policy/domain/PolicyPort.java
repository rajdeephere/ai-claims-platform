package com.claimsai.policy.domain;

import java.util.Optional;

/**
 * Port to the policy administration system (Guidewire PolicyCenter in a real carrier). The claim module
 * depends only on this interface; the adapter behind it can be the demo stub or a real HTTP client.
 */
public interface PolicyPort {

    Optional<PolicySnapshot> findPolicy(String policyNumber);
}
