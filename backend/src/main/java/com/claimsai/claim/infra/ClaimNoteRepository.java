package com.claimsai.claim.infra;

import com.claimsai.claim.domain.ClaimNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimNoteRepository extends JpaRepository<ClaimNote, Long> {

    List<ClaimNote> findByClaimIdOrderByCreatedAtAscIdAsc(Long claimId);
}
