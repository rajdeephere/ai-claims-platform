package com.claimsai.document.infra;

import com.claimsai.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findFirstByClaimIdAndSha256AndStatusIn(Long claimId, String sha256,
                                                             Collection<Document.Status> statuses);

    List<Document> findByClaimIdOrderByCreatedAtAscIdAsc(Long claimId);

    List<Document> findTop100ByStatusAndCreatedAtBefore(Document.Status status, Instant before);
}
