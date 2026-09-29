package com.claimsai.financials.infra;

import com.claimsai.financials.domain.Exposure;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExposureRepository extends JpaRepository<Exposure, Long> {

    List<Exposure> findByClaimIdOrderByIdAsc(Long claimId);

    /**
     * Pessimistic lock (SELECT ... FOR UPDATE) for money decisions on one exposure: two concurrent payment
     * requests are serialised, so the second one sees the reserve the first one used. Claims use
     * optimistic locking (people edit them for minutes); this lock lasts milliseconds.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Exposure e where e.id = :id")
    Optional<Exposure> findByIdForUpdate(@Param("id") Long id);

    long countByClaimIdAndStatus(Long claimId, Exposure.Status status);
}
