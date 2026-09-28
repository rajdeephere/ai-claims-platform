package com.claimsai.claim.app;

import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.Claim.LossReport;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.common.error.BadRequestException;
import com.claimsai.common.util.Hashing;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import com.claimsai.platform.idempotency.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * First notice of loss. Idempotent (ADR-0011): a retry with the same Idempotency-Key returns the claim the
 * first request created instead of a second claim.
 *
 * <p>The transaction is programmatic because of the race: two requests with the same key can both find no
 * record and both insert. The loser's commit fails on the primary key; we catch that <em>after</em> its
 * transaction has rolled back and, in a new transaction, return what the winner created.
 */
@Service
public class FnolService {

    static final String OPERATION = "FNOL";

    private final ClaimRepository claims;
    private final ClaimNumberGenerator claimNumbers;
    private final ClaimIntakeService intake;
    private final ClaimAuditTrail auditTrail;
    private final IdempotencyService idempotency;
    private final UserService users;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public FnolService(ClaimRepository claims, ClaimNumberGenerator claimNumbers, ClaimIntakeService intake,
                       ClaimAuditTrail auditTrail, IdempotencyService idempotency, UserService users,
                       ObjectMapper objectMapper, PlatformTransactionManager transactionManager, Clock clock) {
        this.claims = claims;
        this.claimNumbers = claimNumbers;
        this.intake = intake;
        this.auditTrail = auditTrail;
        this.idempotency = idempotency;
        this.users = users;
        this.objectMapper = objectMapper;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public record FnolResult(Claim claim, boolean replayed) {
    }

    public FnolResult submit(LossReport report, CurrentUser user, String idempotencyKey) {
        String key = IdempotencyService.requireValidKey(idempotencyKey);
        LossReport normalized = normalize(report, user);
        String requestHash = hash(normalized);
        try {
            return transaction.execute(status -> submitOnce(normalized, user, key, requestHash));
        } catch (DataIntegrityViolationException possibleRace) {
            return transaction.execute(status -> replay(user, key, requestHash).orElseThrow(() -> possibleRace));
        }
    }

    private FnolResult submitOnce(LossReport report, CurrentUser user, String key, String requestHash) {
        Optional<FnolResult> previous = replay(user, key, requestHash);
        if (previous.isPresent()) {
            return previous.get();
        }
        // a claimant files for themselves; staff taking a phone FNOL file for someone without a portal login
        Long claimantUserId = user.role() == Role.CLAIMANT ? user.id() : null;
        Claim claim = Claim.submit(claimNumbers.next(), report, claimantUserId, user.id(), LocalDate.now(clock),
                clock.instant());
        claims.save(claim);
        auditTrail.event(claim, "CLAIM_SUBMITTED", ClaimAuditTrail.actor(user), null,
                Map.of("claimNumber", claim.getClaimNumber(), "policyNumber", claim.getPolicyNumber(),
                        "lossType", claim.getLossType().name()), null);
        intake.process(claim);
        idempotency.remember(user.id(), key, OPERATION, requestHash, claim.getId());
        return new FnolResult(claim, false);
    }

    private Optional<FnolResult> replay(CurrentUser user, String key, String requestHash) {
        return idempotency.findPrevious(user.id(), key, OPERATION, requestHash)
                .flatMap(claims::findById)
                .map(claim -> new FnolResult(claim, true));
    }

    /** Defaults and scale applied before hashing, so "1500" and "1500.00" are the same request. */
    private LossReport normalize(LossReport report, CurrentUser user) {
        String contactName = report.contactName();
        if (contactName == null || contactName.isBlank()) {
            if (user.role() != Role.CLAIMANT) {
                throw new BadRequestException("CONTACT_NAME_REQUIRED",
                        "contactName is required when staff report a loss on someone's behalf");
            }
            contactName = users.getActive(user.id()).getDisplayName();
        }
        return new LossReport(report.policyNumber().trim().toUpperCase(), report.lossDate(), report.lossType(),
                report.lossLocation().trim(), report.description().trim(), report.injuriesReported(),
                report.estimatedLoss() == null ? null : report.estimatedLoss().setScale(2, RoundingMode.UNNECESSARY),
                contactName.trim(), report.contactPhone());
    }

    private String hash(LossReport report) {
        try {
            return Hashing.sha256Hex(objectMapper.writeValueAsString(report));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the loss report", e);
        }
    }
}
