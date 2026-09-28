package com.claimsai.audit.app;

import com.claimsai.audit.domain.AuditEvent;
import com.claimsai.audit.infra.AuditEventRepository;
import com.claimsai.common.correlation.CorrelationId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Writes the audit trail. {@code MANDATORY}: an audit entry can only be written inside the caller's business
 * transaction, so a change and its audit record always commit or roll back together. Calling it without a
 * transaction is a bug and fails immediately.
 */
@Service
public class AuditService {

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, Long entityId, Long claimId, String action, AuditActor actor,
                       Map<String, Object> oldValue, Map<String, Object> newValue, String reason) {
        events.save(new AuditEvent(entityType, entityId, claimId, action, actor.userId(), actor.name(),
                oldValue, newValue, reason, CorrelationId.current(), clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> forClaim(Long claimId) {
        return events.findByClaimIdOrderByOccurredAtAscIdAsc(claimId);
    }
}
