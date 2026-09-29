package com.claimsai.financials.infra;

import com.claimsai.financials.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByClaimIdOrderByIdAsc(Long claimId);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.exposureId = :exposureId and p.status in :statuses")
    BigDecimal sumByExposureAndStatus(@Param("exposureId") Long exposureId,
                                      @Param("statuses") Collection<Payment.Status> statuses);

    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.claimId = :claimId and p.status = :status")
    BigDecimal sumByClaimAndStatus(@Param("claimId") Long claimId, @Param("status") Payment.Status status);

    long countByClaimIdAndStatusIn(Long claimId, Collection<Payment.Status> statuses);

    long countByExposureIdAndStatusIn(Long exposureId, Collection<Payment.Status> statuses);
}
