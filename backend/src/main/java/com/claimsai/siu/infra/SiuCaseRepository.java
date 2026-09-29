package com.claimsai.siu.infra;

import com.claimsai.siu.domain.SiuCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiuCaseRepository extends JpaRepository<SiuCase, Long> {

    Page<SiuCase> findByStatus(SiuCase.Status status, Pageable pageable);

    List<SiuCase> findByClaimIdOrderByIdAsc(Long claimId);

    boolean existsByClaimId(Long claimId);

    long countByStatus(SiuCase.Status status);
}
