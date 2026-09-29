package com.claimsai.financials.app;

import com.claimsai.claim.app.ClaimAccess;
import com.claimsai.claim.app.ClaimCommandService;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.financials.domain.ApprovalRequest;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.financials.domain.Payment;
import com.claimsai.financials.infra.ApprovalRequestRepository;
import com.claimsai.financials.infra.ExposureRepository;
import com.claimsai.financials.infra.PaymentRepository;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.platform.jobs.app.JobService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * The checker's side of maker-checker (ADR-0024). Checked in this order, so the most fundamental reason
 * wins: already decided (409) -> own request (422 SELF_APPROVAL) -> SIU hold for payments (422) -> the
 * checker's own authority covers the amount (422 AUTHORITY_EXCEEDED). Then the decision is applied in the
 * same transaction: the payment is released for issuing, the reserve changed, or the claim denied.
 */
@Service
public class ApprovalService {

    private final ApprovalRequestRepository approvals;
    private final PaymentRepository payments;
    private final ExposureRepository exposures;
    private final ClaimAccess claimAccess;
    private final ClaimCommandService claimCommands;
    private final ClaimFinancialsAdapter financials;
    private final AuthorityService authority;
    private final FinancialsAudit audit;
    private final JobService jobs;
    private final Clock clock;

    public ApprovalService(ApprovalRequestRepository approvals, PaymentRepository payments, ExposureRepository exposures,
                           ClaimAccess claimAccess, ClaimCommandService claimCommands, ClaimFinancialsAdapter financials,
                           AuthorityService authority, FinancialsAudit audit, JobService jobs, Clock clock) {
        this.approvals = approvals;
        this.payments = payments;
        this.exposures = exposures;
        this.claimAccess = claimAccess;
        this.claimCommands = claimCommands;
        this.financials = financials;
        this.authority = authority;
        this.audit = audit;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return approvals.countByStatus(ApprovalRequest.Status.PENDING);
    }

    @Transactional(readOnly = true)
    public Page<ApprovalRequest> queue(ApprovalRequest.Status status, Pageable page) {
        return approvals.findByStatus(status, page);
    }

    @Transactional
    public ApprovalRequest approve(Long requestId, String comment, CurrentUser user) {
        ApprovalRequest request = load(requestId);
        Claim claim = claimAccess.loadVisible(request.getClaimId(), user);
        Instant now = clock.instant();
        if (request.getStatus() == ApprovalRequest.Status.PENDING && request.getRequestedBy().equals(user.id())) {
            throw new BusinessRuleException("SELF_APPROVAL", "You can't decide on your own request");
        }
        switch (request.getKind()) {
            case PAYMENT -> {
                if (claim.getStatus() == ClaimStatus.SIU_REVIEW) {
                    throw new BusinessRuleException("SIU_HOLD", "No payments while the claim is under SIU review");
                }
                authority.requireCovers(user.id(), request.getAmount());
                request.approve(user.id(), comment, now);
                Payment payment = payments.findById(request.getTargetId()).orElseThrow();
                payment.approvedBy(user.id());
                PaymentIssuing.schedule(jobs, payment);
            }
            case RESERVE_CHANGE -> {
                authority.requireCovers(user.id(), request.getAmount());
                request.approve(user.id(), comment, now);
                Exposure exposure = exposures.findByIdForUpdate(request.getTargetId()).orElseThrow();
                exposure.setReserve(request.getAmount());
            }
            case DENIAL -> {
                if (financials.committedPayments(claim.getId()) > 0 || otherPendingApprovals(request) > 0) {
                    throw new BusinessRuleException("PENDING_FINANCIALS", "Payments or approvals are still in "
                            + "progress on this claim; resolve them before denying it");
                }
                request.approve(user.id(), comment, now);
                financials.releaseOpenExposures(claim.getId(), user.id(), user.username(), "claim denied");
                claimCommands.applyApprovedDenial(claim.getId(), user.id(), user.username(), request.getReason());
            }
        }
        audit.record("APPROVAL", request.getId(), request.getClaimId(), "APPROVAL_APPROVED", FinancialsAudit.actor(user),
                Map.of("status", "PENDING"), Map.of("status", "APPROVED", "kind", request.getKind().name(),
                        "requestedBy", request.getRequestedBy()), comment);
        return request;
    }

    @Transactional
    public ApprovalRequest reject(Long requestId, String reason, CurrentUser user) {
        ApprovalRequest request = load(requestId);
        claimAccess.loadVisible(request.getClaimId(), user);
        request.reject(user.id(), reason, clock.instant());
        if (request.getKind() == ApprovalRequest.Kind.PAYMENT) {
            payments.findById(request.getTargetId()).orElseThrow().rejected();
        }
        audit.record("APPROVAL", request.getId(), request.getClaimId(), "APPROVAL_REJECTED", FinancialsAudit.actor(user),
                Map.of("status", "PENDING"), Map.of("status", "REJECTED", "kind", request.getKind().name()), reason);
        return request;
    }

    private long otherPendingApprovals(ApprovalRequest denial) {
        return approvals.countByClaimIdAndStatus(denial.getClaimId(), ApprovalRequest.Status.PENDING) - 1;
    }

    private ApprovalRequest load(Long requestId) {
        return approvals.findById(requestId)
                .orElseThrow(() -> new NotFoundException("APPROVAL_NOT_FOUND", "Approval request " + requestId + " not found"));
    }
}
