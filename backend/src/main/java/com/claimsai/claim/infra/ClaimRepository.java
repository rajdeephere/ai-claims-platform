package com.claimsai.claim.infra;

import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, Long>, JpaSpecificationExecutor<Claim> {

    /** A database sequence: unique across instances and never reused, even after a rollback. */
    @Query(value = "SELECT nextval('claim_number_seq')", nativeQuery = true)
    long nextClaimSequence();

    Page<Claim> findByClaimantUserId(Long claimantUserId, Pageable pageable);

    /** Other claims on the same policy with a loss in the given window (fraud signal: frequent claims). */
    long countByPolicyNumberAndIdNotAndLossDateBetween(String policyNumber, Long excludedClaimId, LocalDate from,
                                                       LocalDate to);

    List<Claim> findByPolicyNumberAndIdNotOrderByLossDateDesc(String policyNumber, Long excludedClaimId);

    /** Claims per status for the supervisor dashboard: [status, count]. */
    @Query("select c.status, count(c) from Claim c group by c.status")
    List<Object[]> countByStatus();

    /** Handled claims (past intake, not closed) that nobody is assigned to. */
    @Query("""
            select count(c) from Claim c
            where c.assignedAdjusterId is null and c.status in :statuses""")
    long countUnassigned(@Param("statuses") Collection<ClaimStatus> statuses);

    /** Open workload per adjuster in one query: [adjusterId, count]. */
    @Query("""
            select c.assignedAdjusterId, count(c) from Claim c
            where c.assignedAdjusterId in :adjusterIds and c.status <> :closed
            group by c.assignedAdjusterId""")
    List<Object[]> countActiveByAssignee(@Param("adjusterIds") Collection<Long> adjusterIds,
                                         @Param("closed") ClaimStatus closed);
}
