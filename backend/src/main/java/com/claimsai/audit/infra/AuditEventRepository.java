package com.claimsai.audit.infra;

import com.claimsai.audit.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** id breaks ties between events written in the same instant, keeping their real order. */
    List<AuditEvent> findByClaimIdOrderByOccurredAtAscIdAsc(Long claimId);
}
