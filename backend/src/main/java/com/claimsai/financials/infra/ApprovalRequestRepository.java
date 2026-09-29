package com.claimsai.financials.infra;

import com.claimsai.financials.domain.ApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    Page<ApprovalRequest> findByStatus(ApprovalRequest.Status status, Pageable pageable);

    List<ApprovalRequest> findByClaimIdOrderByIdAsc(Long claimId);

    long countByClaimIdAndStatus(Long claimId, ApprovalRequest.Status status);

    /** At most one per claim for DENIAL (partial unique index). */
    Optional<ApprovalRequest> findByClaimIdAndKindAndStatus(Long claimId, ApprovalRequest.Kind kind,
                                                            ApprovalRequest.Status status);

    Optional<ApprovalRequest> findByKindAndTargetIdAndStatus(ApprovalRequest.Kind kind, Long targetId,
                                                             ApprovalRequest.Status status);
}
