package com.claimsai.financials.infra;

import com.claimsai.financials.domain.Recovery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecoveryRepository extends JpaRepository<Recovery, Long> {

    List<Recovery> findByClaimIdOrderByIdAsc(Long claimId);
}
