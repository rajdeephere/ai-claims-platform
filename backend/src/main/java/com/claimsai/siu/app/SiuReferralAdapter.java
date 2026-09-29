package com.claimsai.siu.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.domain.SiuReferralPort;
import com.claimsai.platform.outbox.app.OutboxService;
import com.claimsai.siu.domain.SiuCase;
import com.claimsai.siu.domain.SiuEvents;
import com.claimsai.siu.infra.SiuCaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * The claim module's {@link SiuReferralPort}. Deliberately depends on no claim service: the claim module's
 * access check uses this port, so depending back on it would be a cycle (in modules and in Spring beans).
 */
@Component
public class SiuReferralAdapter implements SiuReferralPort {

    static final String ENTITY = "SIU_CASE";

    private final SiuCaseRepository cases;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public SiuReferralAdapter(SiuCaseRepository cases, AuditService audit, OutboxService outbox, Clock clock) {
        this.cases = cases;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void openCase(Long claimId, Source source, String reason, Long referredBy, String referredByName,
                         Integer fraudScore) {
        SiuCase siuCase = cases.save(SiuCase.open(claimId, SiuCase.Source.valueOf(source.name()), reason, referredBy,
                fraudScore, clock.instant()));
        Map<String, Object> after = new HashMap<>();
        after.put("caseId", siuCase.getId());
        after.put("source", source.name());
        after.put("fraudScore", fraudScore);
        audit.record(ENTITY, siuCase.getId(), claimId, "SIU_CASE_OPENED",
                referredBy == null ? AuditActor.SYSTEM : new AuditActor(referredBy, referredByName), null, after, reason);
        outbox.append(SiuEvents.AGGREGATE, siuCase.getId(), SiuEvents.SIU_CASE_OPENED, Map.of(
                SiuEvents.CASE_ID, siuCase.getId(), SiuEvents.CLAIM_ID, claimId, SiuEvents.SOURCE, source.name()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasCase(Long claimId) {
        return cases.existsByClaimId(claimId);
    }
}
