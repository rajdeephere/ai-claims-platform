package com.claimsai.claim.infra;

import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

    /** A database sequence: unique across instances and never reused, even after a rollback. */
    @Query(value = "SELECT nextval('claim_number_seq')", nativeQuery = true)
    long nextClaimSequence();

    Page<Claim> findByClaimantUserId(Long claimantUserId, Pageable pageable);

    /** Open workload per adjuster in one query: [adjusterId, count]. */
    @Query("""
            select c.assignedAdjusterId, count(c) from Claim c
            where c.assignedAdjusterId in :adjusterIds and c.status <> :closed
            group by c.assignedAdjusterId""")
    List<Object[]> countActiveByAssignee(@Param("adjusterIds") Collection<Long> adjusterIds,
                                         @Param("closed") ClaimStatus closed);
}
