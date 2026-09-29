package com.claimsai.activity.infra;

import com.claimsai.activity.domain.Activity;
import com.claimsai.activity.domain.ActivityType;
import com.claimsai.identity.domain.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    /** My work: assigned to me, or in my role's queue. */
    @Query("""
            select a from Activity a
            where a.status = :status and (a.assigneeId = :userId or a.candidateRole = :role)""")
    Page<Activity> findOwnedBy(@Param("userId") Long userId, @Param("role") Role role,
                               @Param("status") Activity.Status status, Pageable pageable);

    @Query("""
            select a from Activity a
            where a.status = :status and (a.assigneeId = :userId or a.candidateRole = :role) and a.dueAt < :now""")
    Page<Activity> findOwnedByDueBefore(@Param("userId") Long userId, @Param("role") Role role,
                                        @Param("status") Activity.Status status, @Param("now") Instant now,
                                        Pageable pageable);

    Page<Activity> findByStatusAndEscalatedAtIsNotNull(Activity.Status status, Pageable pageable);

    List<Activity> findByClaimIdOrderByIdAsc(Long claimId);

    List<Activity> findByClaimIdAndStatus(Long claimId, Activity.Status status);

    boolean existsByClaimIdAndType(Long claimId, ActivityType type);

    /**
     * The open activity of a type about a linked record. linkedKey is linkedId, or 0 for none: the same key
     * as the one-open unique index, and never a null parameter (null never equals anything in SQL, BUG-011).
     */
    @Query("""
            select a from Activity a
            where a.claimId = :claimId and a.type = :type and coalesce(a.linkedId, 0) = :linkedKey
              and a.status = :status""")
    Optional<Activity> findByKey(@Param("claimId") Long claimId, @Param("type") ActivityType type,
                                 @Param("linkedKey") long linkedKey, @Param("status") Activity.Status status);

    long countByStatus(Activity.Status status);

    long countByStatusAndEscalatedAtIsNotNull(Activity.Status status);

    /** SLA breaches still open, per owner: [assigneeId, candidateRole, count]. */
    @Query("""
            select a.assigneeId, a.candidateRole, count(a) from Activity a
            where a.status = :status and a.escalatedAt is not null
            group by a.assigneeId, a.candidateRole""")
    List<Object[]> countBreachedByOwner(@Param("status") Activity.Status status);
}
