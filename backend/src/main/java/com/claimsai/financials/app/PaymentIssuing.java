package com.claimsai.financials.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.claim.app.ClaimQueryService.ClaimFacts;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.financials.domain.Exposure;
import com.claimsai.financials.domain.FinancialsEvents;
import com.claimsai.financials.domain.Payment;
import com.claimsai.financials.domain.PaymentRail;
import com.claimsai.financials.domain.PaymentRail.PaymentRefusedException;
import com.claimsai.financials.domain.PaymentRail.RailUnavailableException;
import com.claimsai.financials.infra.ExposureRepository;
import com.claimsai.financials.infra.PaymentRepository;
import com.claimsai.platform.jobs.app.JobContext;
import com.claimsai.platform.jobs.app.JobHandler;
import com.claimsai.platform.jobs.app.JobService;
import com.claimsai.platform.jobs.app.JobService.JobRequest;
import com.claimsai.platform.outbox.app.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The ISSUE_PAYMENT job: sends an approved payment to the payment rail (ADR-0024).
 * <ul>
 *   <li>the rail is called outside any transaction, with the payment's own idempotency key</li>
 *   <li>rail unavailable: the job retries with backoff, same key. The payment stays APPROVED, never FAILED:
 *       after a timeout nobody knows whether money moved, and only the rail's answer to the same key can
 *       tell. After the last attempt: a PAYMENT_STUCK event (a task for the supervisors) and the job lands
 *       in the ops queue; retrying it there reuses the same key, so it can't pay twice</li>
 *   <li>rail refuses (account closed): FAILED, and the reserve is available again</li>
 *   <li>claim under SIU review: held, checked again in 30 minutes</li>
 *   <li>success: ISSUED + paid amount on the exposure + audit + PAYMENT_ISSUED event, in one transaction.
 *       If that transaction fails after the rail paid, the retry gets the same reference back</li>
 * </ul>
 */
@Component
public class PaymentIssuing implements JobHandler {

    public static final String JOB_TYPE = "ISSUE_PAYMENT";
    private static final Logger log = LoggerFactory.getLogger(PaymentIssuing.class);
    private static final Duration SIU_HOLD_RECHECK = Duration.ofMinutes(30);

    private final PaymentRepository payments;
    private final ExposureRepository exposures;
    private final PaymentRail rail;
    private final ClaimQueryService claims;
    private final FinancialsAudit audit;
    private final OutboxService outbox;
    private final JobService jobs;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PaymentIssuing(PaymentRepository payments, ExposureRepository exposures, PaymentRail rail,
                          ClaimQueryService claims, FinancialsAudit audit, OutboxService outbox, JobService jobs,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.payments = payments;
        this.exposures = exposures;
        this.rail = rail;
        this.claims = claims;
        this.audit = audit;
        this.outbox = outbox;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Called in the approving transaction: the approval and its issuing job commit together. */
    static void schedule(JobService jobs, Payment payment) {
        jobs.schedule(new JobRequest(JOB_TYPE, payment.getClaimId(), Map.of("paymentId", payment.getId()), Duration.ZERO,
                JOB_TYPE + ":" + payment.getId()));
    }

    @Override
    public String type() {
        return JOB_TYPE;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext job) {
        Long paymentId = ((Number) job.payload().get("paymentId")).longValue();
        Payment payment = transaction.execute(s -> payments.findById(paymentId).orElseThrow());
        if (payment.getStatus() != Payment.Status.APPROVED) {
            return;   // issued, failed or rejected already
        }
        ClaimFacts claim = claims.facts(payment.getClaimId());
        if (claim.status() == ClaimStatus.SIU_REVIEW) {
            transaction.executeWithoutResult(s -> jobs.schedule(new JobRequest(JOB_TYPE, payment.getClaimId(),
                    Map.of("paymentId", paymentId), SIU_HOLD_RECHECK,
                    JOB_TYPE + ":" + paymentId + ":hold-" + clock.millis())));
            log.info("Payment {} held: claim {} is under SIU review", paymentId, claim.claimNumber());
            return;
        }

        String reference;
        try {
            reference = rail.issue(new PaymentRail.Instruction(payment.getIdempotencyKey(), payment.getAmount(),
                    payment.getPayeeName(), claim.claimNumber()));   // no transaction held
        } catch (PaymentRefusedException refused) {
            transaction.executeWithoutResult(s -> {
                Payment current = payments.findById(paymentId).orElseThrow();
                current.failed(refused.getMessage());
                audit.record("PAYMENT", paymentId, current.getClaimId(), "PAYMENT_FAILED", AuditActor.SYSTEM, null,
                        Map.of("paymentId", paymentId, "amount", current.getAmount()), refused.getMessage());
                outbox.append(FinancialsEvents.AGGREGATE, paymentId, FinancialsEvents.PAYMENT_FAILED,
                        event(current, claim));
            });
            return;
        } catch (RailUnavailableException unavailable) {
            if (job.isLastAttempt()) {
                // still APPROVED: the money may have left. A person must check with the bank.
                transaction.executeWithoutResult(s -> {
                    Payment current = payments.findById(paymentId).orElseThrow();
                    audit.record("PAYMENT", paymentId, current.getClaimId(), "PAYMENT_STATUS_UNKNOWN", AuditActor.SYSTEM,
                            null, Map.of("paymentId", paymentId, "attempts", job.attempt()), unavailable.getMessage());
                    outbox.append(FinancialsEvents.AGGREGATE, paymentId, FinancialsEvents.PAYMENT_STUCK,
                            event(current, claim));
                });
            }
            throw unavailable;   // the job queue retries with the same idempotency key (or marks the job FAILED)
        }

        transaction.executeWithoutResult(s -> {
            Payment current = payments.findById(paymentId).orElseThrow();
            Exposure exposure = exposures.findByIdForUpdate(current.getExposureId()).orElseThrow();
            exposure.recordPayment(current.getAmount());
            current.issued(reference, clock.instant());
            audit.record("PAYMENT", paymentId, current.getClaimId(), "PAYMENT_ISSUED", AuditActor.SYSTEM, null,
                    Map.of("paymentId", paymentId, "amount", current.getAmount(), "reference", reference), null);
            outbox.append(FinancialsEvents.AGGREGATE, paymentId, FinancialsEvents.PAYMENT_ISSUED, event(current, claim));
        });
    }

    private static Map<String, Object> event(Payment payment, ClaimFacts claim) {
        Map<String, Object> event = new HashMap<>();
        event.put(FinancialsEvents.CLAIM_ID, payment.getClaimId());
        event.put(FinancialsEvents.CLAIM_NUMBER, claim.claimNumber());
        event.put(FinancialsEvents.PAYMENT_ID, payment.getId());
        // money as a string: a JSON number comes back as a double, and "3800.0" is not an amount (BUG-012)
        event.put(FinancialsEvents.AMOUNT, payment.getAmount().toPlainString());
        event.put(FinancialsEvents.PAYEE, payment.getPayeeName());
        if (claim.claimantUserId() != null) {
            event.put(FinancialsEvents.CLAIMANT_USER_ID, claim.claimantUserId());
        }
        return event;
    }
}
