package com.claimsai.claim.infra;

import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.claim.domain.ClaimStatus;
import org.springframework.data.jpa.domain.Specification;

/** Optional queue filters, combined only when present (no "(:x is null or ...)" SQL). */
public final class ClaimSpecifications {

    private ClaimSpecifications() {
    }

    public static Specification<Claim> queue(ClaimStatus status, Segment segment, Long assigneeId) {
        Specification<Claim> spec = (root, query, cb) -> cb.conjunction();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (segment != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("segment"), segment));
        }
        if (assigneeId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("assignedAdjusterId"), assigneeId));
        }
        return spec;
    }
}
