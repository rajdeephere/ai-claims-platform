package com.claimsai.financials.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.app.ClaimAccess;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.InvalidTransitionException;
import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.ConflictException;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.common.util.Hashing;
import com.claimsai.common.web.ETags;
import com.claimsai.financials.domain.ApprovalRequest;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.financials.domain.Money;
import com.claimsai.financials.domain.Payment;
import com.claimsai.financials.domain.Recovery;
import com.claimsai.financials.infra.ApprovalRequestRepository;
import com.claimsai.financials.infra.ExposureRepository;
import com.claimsai.financials.infra.PaymentRepository;
import com.claimsai.financials.infra.RecoveryRepository;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.platform.idempotency.IdempotencyService;
import com.claimsai.platform.jobs.app.JobService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Exposures, reserves, payments, denial requests and recoveries (ADR-0024). The rules:
 * <ul>
 *   <li>access follows the claim (visible 404, permitted 403, status 409); payments on a claim under SIU
 *       review are refused (422 SIU_HOLD)</li>
 *   <li>a payment never exceeds the exposure's available reserve: reserve - paid - payments in progress;
 *       concurrent requests on one exposure are serialised by a row lock</li>
 *   <li>within the requester's authority limit a payment or reserve is applied at once; above it, an
 *       approval request goes to a supervisor (maker-checker)</li>
 *   <li>every decision is audited</li>
 * </ul>
 */
@Service
public class FinancialsService {

    static final EnumSet<Payment.Status> COMMITTED = EnumSet.of(Payment.Status.PENDING_APPROVAL, Payment.Status.APPROVED);
    private static final String PAYMENT_OPERATION = "PAYMENT";

    private final ClaimAccess claimAccess;
    private final ExposureRepository exposures;
    private final PaymentRepository payments;
    private final ApprovalRequestRepository approvals;
    private final RecoveryRepository recoveries;
    private final AuthorityService authority;
    private final FinancialsAudit audit;
    private final JobService jobs;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public FinancialsService(ClaimAccess claimAccess, ExposureRepository exposures, PaymentRepository payments,
                             ApprovalRequestRepository approvals, RecoveryRepository recoveries,
                             AuthorityService authority, FinancialsAudit audit, JobService jobs,
                             IdempotencyService idempotency, PlatformTransactionManager transactionManager, Clock clock) {
        this.claimAccess = claimAccess;
        this.exposures = exposures;
        this.payments = payments;
        this.approvals = approvals;
        this.recoveries = recoveries;
        this.authority = authority;
        this.audit = audit;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // ---------- exposures and reserves ----------

    public record ReserveOutcome(Exposure exposure, ApprovalRequest pendingApproval) {
    }

    @Transactional
    public ReserveOutcome createExposure(Long claimId, Exposure.Type type, String claimantName, BigDecimal reserve,
                                         String reason, CurrentUser user) {
        Claim claim = access(claimId, user, ClaimAction.MANAGE_EXPOSURES);
        Exposure exposure = exposures.save(Exposure.open(claim.getId(), type, claim.getLossType().coverage().name(),
                claimantName, user.id(), clock.instant()));
        audit.record("EXPOSURE", exposure.getId(), claim.getId(), "EXPOSURE_CREATED", FinancialsAudit.actor(user), null,
                Map.of("type", type.name(), "coverage", exposure.getCoverageType(), "claimant", claimantName), reason);
        ApprovalRequest pending = reserve == null || reserve.signum() == 0 ? null
                : applyReserve(exposure, Money.of(reserve), reason, user);
        return new ReserveOutcome(exposure, pending);
    }

    @Transactional
    public ReserveOutcome changeReserve(Long exposureId, String ifMatch, BigDecimal amount, String reason,
                                        CurrentUser user) {
        Exposure exposure = lockedExposure(exposureId, user, ClaimAction.MANAGE_EXPOSURES);
        ETags.requireMatch(ifMatch, exposure.getVersion());
        return new ReserveOutcome(exposure, applyReserve(exposure, Money.of(amount), reason, user));
    }

    /** Lowering is always allowed (it releases money); raising above your limit needs approval. */
    private ApprovalRequest applyReserve(Exposure exposure, BigDecimal amount, String reason, CurrentUser user) {
        if (!exposure.isOpen()) {
            throw new ConflictException("EXPOSURE_CLOSED", "Exposure " + exposure.getId() + " is closed");
        }
        BigDecimal committed = payments.sumByExposureAndStatus(exposure.getId(), COMMITTED);
        if (amount.compareTo(exposure.getPaidAmount().add(committed)) < 0) {
            throw new BusinessRuleException("RESERVE_BELOW_COMMITTED", "The reserve can't be below what is paid or "
                    + "being paid (" + exposure.getPaidAmount().add(committed) + ")");
        }
        if (approvals.findByKindAndTargetIdAndStatus(ApprovalRequest.Kind.RESERVE_CHANGE, exposure.getId(),
                ApprovalRequest.Status.PENDING).isPresent()) {
            throw new ConflictException("APPROVAL_PENDING", "A reserve change for this exposure is already waiting "
                    + "for approval");
        }
        boolean decrease = amount.compareTo(exposure.getReserveAmount()) <= 0;
        if (decrease || authority.covers(user.id(), amount)) {
            BigDecimal before = exposure.getReserveAmount();
            exposure.setReserve(amount);
            audit.record("EXPOSURE", exposure.getId(), exposure.getClaimId(), "RESERVE_CHANGED",
                    FinancialsAudit.actor(user), Map.of("reserve", before), Map.of("reserve", amount), reason);
            return null;
        }
        ApprovalRequest request = approvals.save(ApprovalRequest.pending(exposure.getClaimId(),
                ApprovalRequest.Kind.RESERVE_CHANGE, exposure.getId(), amount, reasonOr(reason, "reserve increase"),
                user.id(), clock.instant()));
        audit.record("APPROVAL", request.getId(), exposure.getClaimId(), "APPROVAL_REQUESTED", FinancialsAudit.actor(user),
                null, Map.of("kind", "RESERVE_CHANGE", "exposureId", exposure.getId(), "amount", amount), reason);
        return request;
    }

    @Transactional
    public Exposure closeExposure(Long exposureId, String ifMatch, String reason, CurrentUser user) {
        Exposure exposure = lockedExposure(exposureId, user, ClaimAction.MANAGE_EXPOSURES);
        ETags.requireMatch(ifMatch, exposure.getVersion());
        if (payments.countByExposureIdAndStatusIn(exposureId, COMMITTED) > 0
                || approvals.findByKindAndTargetIdAndStatus(ApprovalRequest.Kind.RESERVE_CHANGE, exposureId,
                ApprovalRequest.Status.PENDING).isPresent()) {
            throw new BusinessRuleException("PENDING_FINANCIALS", "A payment or reserve change on this exposure is "
                    + "still in progress");
        }
        BigDecimal released = exposure.getReserveAmount().subtract(exposure.getPaidAmount());
        exposure.close(clock.instant());
        audit.record("EXPOSURE", exposure.getId(), exposure.getClaimId(), "EXPOSURE_CLOSED", FinancialsAudit.actor(user),
                null, Map.of("paid", exposure.getPaidAmount(), "releasedReserve", released), reason);
        return exposure;
    }

    // ---------- payments ----------

    public record PaymentOutcome(Payment payment, boolean replayed) {
    }

    /** Idempotent with the client's Idempotency-Key, like FNOL (ADR-0011). */
    public PaymentOutcome requestPayment(Long exposureId, BigDecimal amount, String payeeName, String reason,
                                         CurrentUser user, String idempotencyKey) {
        String key = IdempotencyService.requireValidKey(idempotencyKey);
        BigDecimal normalized = Money.of(amount);
        String hash = Hashing.sha256Hex(exposureId + "|" + normalized.toPlainString() + "|" + payeeName.strip());
        try {
            return transaction.execute(s -> replay(user, key, hash)
                    .orElseGet(() -> newPayment(exposureId, normalized, payeeName.strip(), reason, user, key, hash)));
        } catch (DataIntegrityViolationException possibleRace) {
            return transaction.execute(s -> replay(user, key, hash).orElseThrow(() -> possibleRace));
        }
    }

    private Optional<PaymentOutcome> replay(CurrentUser user, String key, String hash) {
        return idempotency.findPrevious(user.id(), key, PAYMENT_OPERATION, hash).flatMap(payments::findById)
                .map(p -> new PaymentOutcome(p, true));
    }

    private PaymentOutcome newPayment(Long exposureId, BigDecimal amount, String payeeName, String reason,
                                      CurrentUser user, String key, String hash) {
        // the row lock serialises payments on this exposure: the next request sees this one's commitment
        Exposure exposure = lockedExposure(exposureId, user, ClaimAction.REQUEST_PAYMENT);
        if (!exposure.isOpen()) {
            throw new ConflictException("EXPOSURE_CLOSED", "Exposure " + exposureId + " is closed");
        }
        BigDecimal available = exposure.available(payments.sumByExposureAndStatus(exposureId, COMMITTED));
        if (amount.compareTo(available) > 0) {
            throw new BusinessRuleException("INSUFFICIENT_RESERVE", "Only " + available + " of the reserve is "
                    + "available; raise the reserve first");
        }
        Instant now = clock.instant();
        boolean withinAuthority = authority.covers(user.id(), amount);
        Payment payment = payments.save(Payment.requested(exposure.getClaimId(), exposureId, amount, payeeName,
                UUID.randomUUID().toString(), user.id(), withinAuthority, now));
        Map<String, Object> details = new HashMap<>();
        details.put("paymentId", payment.getId());
        details.put("amount", amount);
        details.put("payee", payeeName);
        if (withinAuthority) {
            PaymentIssuing.schedule(jobs, payment);
            audit.record("PAYMENT", payment.getId(), exposure.getClaimId(), "PAYMENT_APPROVED", FinancialsAudit.actor(user),
                    null, details, "within the requester's authority limit");
        } else {
            ApprovalRequest request = approvals.save(ApprovalRequest.pending(exposure.getClaimId(),
                    ApprovalRequest.Kind.PAYMENT, payment.getId(), amount, reasonOr(reason, "payment above authority"),
                    user.id(), now));
            payment.awaitApproval(request.getId());
            audit.record("APPROVAL", request.getId(), exposure.getClaimId(), "APPROVAL_REQUESTED",
                    FinancialsAudit.actor(user), null, details, reason);
        }
        idempotency.remember(user.id(), key, PAYMENT_OPERATION, hash, payment.getId());
        return new PaymentOutcome(payment, false);
    }

    // ---------- denial and recovery ----------

    /** A denial is always a supervisor's decision: the adjuster only proposes it. */
    @Transactional
    public ApprovalRequest requestDenial(Long claimId, String reason, CurrentUser user) {
        Claim claim = access(claimId, user, ClaimAction.REQUEST_DENIAL);
        return denialRequest(claim.getId(), reason, new AuditActor(user.id(), user.username()));
    }

    /**
     * SIU confirmed fraud: the investigator proposes the denial, a supervisor still decides. Called inside
     * the SIU outcome's transaction, which has already checked who may do this.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequest proposeDenial(Long claimId, String reason, Long requesterId, String requesterName) {
        return denialRequest(claimId, reason, new AuditActor(requesterId, requesterName));
    }

    private ApprovalRequest denialRequest(Long claimId, String reason, AuditActor requester) {
        if (approvals.findByClaimIdAndKindAndStatus(claimId, ApprovalRequest.Kind.DENIAL, ApprovalRequest.Status.PENDING)
                .isPresent()) {
            throw new ConflictException("APPROVAL_PENDING", "A denial for this claim is already waiting for approval");
        }
        ApprovalRequest request = approvals.save(ApprovalRequest.pending(claimId, ApprovalRequest.Kind.DENIAL,
                null, null, reason, requester.userId(), clock.instant()));
        audit.record("APPROVAL", request.getId(), claimId, "APPROVAL_REQUESTED", requester, null,
                Map.of("kind", "DENIAL"), reason);
        return request;
    }

    @Transactional
    public Recovery recordRecovery(Long claimId, BigDecimal amount, Recovery.Source source, String reference,
                                   LocalDate receivedOn, CurrentUser user) {
        Claim claim = access(claimId, user, ClaimAction.RECORD_RECOVERY);
        if (payments.sumByClaimAndStatus(claimId, Payment.Status.ISSUED).signum() == 0) {
            throw new BusinessRuleException("NO_PAYMENT_TO_RECOVER", "Nothing was paid on this claim yet");
        }
        if (receivedOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleException("DATE_IN_FUTURE", "The recovery can't be received in the future");
        }
        Recovery recovery = recoveries.save(new Recovery(claim.getId(), amount, source, reference, receivedOn, user.id(),
                clock.instant()));
        audit.record("RECOVERY", recovery.getId(), claim.getId(), "RECOVERY_RECORDED", FinancialsAudit.actor(user), null,
                Map.of("amount", recovery.getAmount(), "source", source.name()), reference);
        return recovery;
    }

    // ---------- reading ----------

    public record ExposureView(Exposure exposure, BigDecimal committed, BigDecimal available) {
    }

    @Transactional(readOnly = true)
    public List<ExposureView> exposures(Long claimId, CurrentUser user) {
        Claim claim = claimAccess.loadVisible(claimId, user);
        return exposures.findByClaimIdOrderByIdAsc(claim.getId()).stream().map(e -> {
            BigDecimal committed = payments.sumByExposureAndStatus(e.getId(), COMMITTED);
            return new ExposureView(e, committed, e.isOpen() ? e.available(committed) : Money.ZERO);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Payment> payments(Long claimId, CurrentUser user) {
        return payments.findByClaimIdOrderByIdAsc(claimAccess.loadVisible(claimId, user).getId());
    }

    @Transactional(readOnly = true)
    public List<Recovery> recoveries(Long claimId, CurrentUser user) {
        return recoveries.findByClaimIdOrderByIdAsc(claimAccess.loadVisible(claimId, user).getId());
    }

    // ---------- helpers ----------

    private Exposure lockedExposure(Long exposureId, CurrentUser user, ClaimAction action) {
        NotFoundException notFound = new NotFoundException("EXPOSURE_NOT_FOUND", "Exposure " + exposureId + " not found");
        Exposure exposure = exposures.findByIdForUpdate(exposureId).orElseThrow(() -> notFound);
        try {
            access(exposure.getClaimId(), user, action);
        } catch (NotFoundException hidden) {
            throw notFound;
        }
        return exposure;
    }

    /** visible (404) -> permitted (403) -> SIU hold for payments (422) -> status (409) */
    private Claim access(Long claimId, CurrentUser user, ClaimAction action) {
        Claim claim = claimAccess.loadVisible(claimId, user);
        claimAccess.requirePermission(action, claim, user);
        if (action == ClaimAction.REQUEST_PAYMENT && claim.getStatus() == ClaimStatus.SIU_REVIEW) {
            throw new BusinessRuleException("SIU_HOLD", "No payments while the claim is under SIU review");
        }
        if (!action.availableIn(claim.getStatus())) {
            throw new InvalidTransitionException(action.name().toLowerCase().replace('_', ' ') + " on", claim.getStatus());
        }
        return claim;
    }

    private static String reasonOr(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }
}
