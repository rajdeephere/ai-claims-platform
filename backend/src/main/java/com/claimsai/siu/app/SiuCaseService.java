package com.claimsai.siu.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.app.ClaimAccess;
import com.claimsai.claim.app.ClaimCommandService;
import com.claimsai.claim.app.ClaimQueryService;
import com.claimsai.claim.app.ClaimQueryService.ClaimFacts;
import com.claimsai.claim.app.ClaimQueryService.PolicyClaim;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.financials.app.FinancialsService;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.app.UserRef;
import com.claimsai.identity.app.UserService;
import com.claimsai.platform.outbox.app.OutboxService;
import com.claimsai.siu.domain.SiuCase;
import com.claimsai.siu.domain.SiuEvents;
import com.claimsai.siu.infra.SiuCaseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The SIU investigator's work (design F7): the queue of cases, what they need to investigate, and the
 * outcome. The outcome ends the SIU review in the same transaction:
 * <ul>
 *   <li>CLEARED: the claim goes back to OPEN and normal handling resumes (held payments are issued)</li>
 *   <li>CONFIRMED: back to OPEN with a denial proposed by the investigator; a supervisor still decides
 *       (the system never denies on its own, and the maker is never the checker)</li>
 * </ul>
 */
@Service
public class SiuCaseService {

    private final SiuCaseRepository cases;
    private final ClaimAccess claimAccess;
    private final ClaimQueryService claimQueries;
    private final ClaimCommandService claimCommands;
    private final FinancialsService financials;
    private final UserService users;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public SiuCaseService(SiuCaseRepository cases, ClaimAccess claimAccess, ClaimQueryService claimQueries,
                          ClaimCommandService claimCommands, FinancialsService financials, UserService users,
                          AuditService audit, OutboxService outbox, Clock clock) {
        this.cases = cases;
        this.claimAccess = claimAccess;
        this.claimQueries = claimQueries;
        this.claimCommands = claimCommands;
        this.financials = financials;
        this.users = users;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** A case with the names a screen shows. */
    public record CaseView(SiuCase siuCase, String claimNumber, UserRef referredBy, UserRef investigator) {
    }

    /** What an investigator looks at: the case, the claim, and the policy's other claims. */
    public record CaseFile(CaseView view, ClaimFacts claim, Integer currentFraudScore, List<PolicyClaim> otherClaimsOnPolicy) {
    }

    @Transactional(readOnly = true)
    public Page<CaseView> queue(SiuCase.Status status, Pageable page) {
        Page<SiuCase> result = cases.findByStatus(status, page);
        Map<Long, String> numbers = claimQueries.claimNumbers(result.map(SiuCase::getClaimId).toList());
        Map<Long, UserRef> people = users.refs(result.getContent().stream()
                .flatMap(c -> Stream.of(c.getReferredBy(), c.getInvestigatorId())).filter(Objects::nonNull).distinct()
                .toList());
        return result.map(c -> new CaseView(c, numbers.get(c.getClaimId()), people.get(c.getReferredBy()),
                people.get(c.getInvestigatorId())));
    }

    @Transactional(readOnly = true)
    public CaseFile caseFile(Long caseId) {
        SiuCase siuCase = load(caseId);
        ClaimFacts claim = claimQueries.facts(siuCase.getClaimId());
        return new CaseFile(view(siuCase, claim.claimNumber()), claim, claimQueries.fraudScore(siuCase.getClaimId()),
                claimQueries.otherClaimsOnPolicy(siuCase.getClaimId()));
    }

    /** For staff on the claim screen: its investigations, if they can see the claim. */
    @Transactional(readOnly = true)
    public List<CaseView> forClaim(Long claimId, CurrentUser user) {
        var claim = claimAccess.loadVisible(claimId, user);
        return cases.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .map(c -> view(c, claim.getClaimNumber())).toList();
    }

    @Transactional
    public CaseView recordOutcome(Long caseId, SiuCase.Outcome outcome, String findings, CurrentUser investigator) {
        SiuCase siuCase = load(caseId);
        siuCase.decide(outcome, investigator.id(), findings, clock.instant());
        claimCommands.completeSiuReview(siuCase.getClaimId(), investigator.id(), investigator.username(), outcome.name());
        if (outcome == SiuCase.Outcome.CONFIRMED) {
            financials.proposeDenial(siuCase.getClaimId(), "SIU confirmed fraud: " + findings, investigator.id(),
                    investigator.username());
        }
        audit.record(SiuReferralAdapter.ENTITY, siuCase.getId(), siuCase.getClaimId(), "SIU_CASE_DECIDED",
                new AuditActor(investigator.id(), investigator.username()), Map.of("status", "OPEN"),
                Map.of("status", siuCase.getStatus().name()), findings);
        outbox.append(SiuEvents.AGGREGATE, siuCase.getId(), SiuEvents.SIU_CASE_DECIDED, Map.of(
                SiuEvents.CASE_ID, siuCase.getId(), SiuEvents.CLAIM_ID, siuCase.getClaimId(),
                SiuEvents.OUTCOME, outcome.name()));
        cases.flush();   // a concurrent decision fails here (version), as a 409
        return view(siuCase, claimQueries.facts(siuCase.getClaimId()).claimNumber());
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return cases.countByStatus(SiuCase.Status.OPEN);
    }

    private CaseView view(SiuCase c, String claimNumber) {
        Map<Long, UserRef> people = users.refs(Stream.of(c.getReferredBy(), c.getInvestigatorId())
                .filter(Objects::nonNull).distinct().toList());
        return new CaseView(c, claimNumber, people.get(c.getReferredBy()), people.get(c.getInvestigatorId()));
    }

    private SiuCase load(Long caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new NotFoundException("SIU_CASE_NOT_FOUND", "SIU case " + caseId + " not found"));
    }
}
