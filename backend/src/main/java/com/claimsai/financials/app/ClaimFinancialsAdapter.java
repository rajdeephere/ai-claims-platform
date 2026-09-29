package com.claimsai.financials.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.domain.ClaimFinancialsPort;
import com.claimsai.financials.domain.ApprovalRequest;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.financials.domain.Payment;
import com.claimsai.financials.infra.ApprovalRequestRepository;
import com.claimsai.financials.infra.ExposureRepository;
import com.claimsai.financials.infra.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Map;

/** The claim module's financials port, implemented here (dependency inversion: no module cycle). */
@Service
public class ClaimFinancialsAdapter implements ClaimFinancialsPort {

    private final ExposureRepository exposures;
    private final PaymentRepository payments;
    private final ApprovalRequestRepository approvals;
    private final FinancialsAudit audit;
    private final Clock clock;

    public ClaimFinancialsAdapter(ExposureRepository exposures, PaymentRepository payments,
                                  ApprovalRequestRepository approvals, FinancialsAudit audit, Clock clock) {
        this.exposures = exposures;
        this.payments = payments;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Position position(Long claimId) {
        int open = (int) exposures.countByClaimIdAndStatus(claimId, Exposure.Status.OPEN);
        int pending = (int) (approvals.countByClaimIdAndStatus(claimId, ApprovalRequest.Status.PENDING)
                + payments.countByClaimIdAndStatusIn(claimId, EnumSet.of(Payment.Status.APPROVED)));
        BigDecimal paid = payments.sumByClaimAndStatus(claimId, Payment.Status.ISSUED);
        return new Position(open, pending, paid.signum() > 0, paid);
    }

    long committedPayments(Long claimId) {
        return payments.countByClaimIdAndStatusIn(claimId, FinancialsService.COMMITTED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseOpenExposures(Long claimId, Long actorId, String actorName, String reason) {
        for (Exposure exposure : exposures.findByClaimIdOrderByIdAsc(claimId)) {
            if (exposure.isOpen()) {
                BigDecimal released = exposure.getReserveAmount().subtract(exposure.getPaidAmount());
                exposure.close(clock.instant());
                audit.record("EXPOSURE", exposure.getId(), claimId, "EXPOSURE_CLOSED", new AuditActor(actorId, actorName),
                        null, Map.of("paid", exposure.getPaidAmount(), "releasedReserve", released), reason);
            }
        }
    }
}
