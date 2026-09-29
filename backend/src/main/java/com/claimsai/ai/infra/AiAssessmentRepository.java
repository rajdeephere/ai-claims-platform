package com.claimsai.ai.infra;

import com.claimsai.ai.domain.AiAssessment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiAssessmentRepository extends JpaRepository<AiAssessment, Long> {

    List<AiAssessment> findByClaimIdOrderByCreatedAtAscIdAsc(Long claimId);

    List<AiAssessment> findByClaimIdAndKindAndStatus(Long claimId, AiAssessment.Kind kind, AiAssessment.Status status);

    boolean existsByDocumentIdAndKindAndStatus(Long documentId, AiAssessment.Kind kind, AiAssessment.Status status);
}
