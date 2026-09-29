package com.claimsai.document.infra;

import com.claimsai.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findFirstByClaimIdAndSha256AndStatusIn(Long claimId, String sha256,
                                                             Collection<Document.Status> statuses);

    List<Document> findByClaimIdOrderByCreatedAtAscIdAsc(Long claimId);

    List<Document> findTop100ByStatusAndCreatedAtBefore(Document.Status status, Instant before);

    boolean existsByClaimIdAndStatusIn(Long claimId, Collection<Document.Status> statuses);

    /** Documents of this claim whose exact bytes also sit on another claim (fraud signal). */
    @Query(value = """
            SELECT count(*) FROM document d
            WHERE d.claim_id = :claimId AND d.sha256 IS NOT NULL
              AND d.status IN ('UPLOADED', 'PROCESSING', 'PROCESSED', 'FAILED')
              AND EXISTS (SELECT 1 FROM document o
                          WHERE o.sha256 = d.sha256 AND o.claim_id <> d.claim_id
                            AND o.status IN ('UPLOADED', 'PROCESSING', 'PROCESSED', 'FAILED'))""",
            nativeQuery = true)
    long countDuplicatesOnOtherClaims(@Param("claimId") Long claimId);
}
