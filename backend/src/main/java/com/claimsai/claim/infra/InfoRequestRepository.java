package com.claimsai.claim.infra;

import com.claimsai.claim.domain.InfoRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InfoRequestRepository extends JpaRepository<InfoRequest, Long> {

    Optional<InfoRequest> findByClaimIdAndStatus(Long claimId, InfoRequest.Status status);
}
