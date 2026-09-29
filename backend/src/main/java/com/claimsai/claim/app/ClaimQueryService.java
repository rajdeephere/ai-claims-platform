package com.claimsai.claim.app;

import com.claimsai.audit.app.AuditService;
import com.claimsai.audit.domain.AuditEvent;
import com.claimsai.claim.app.ClaimDetails.TimelineEntry;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.ClaimFinancialsPort;
import com.claimsai.claim.domain.ClaimEnums.Segment;
import com.claimsai.claim.domain.ClaimNote;
import com.claimsai.claim.domain.ClaimStatus;
import com.claimsai.claim.domain.InfoRequest;
import com.claimsai.claim.infra.ClaimNoteRepository;
import com.claimsai.claim.infra.ClaimRepository;
import com.claimsai.claim.infra.ClaimSpecifications;
import com.claimsai.claim.infra.InfoRequestRepository;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.app.UserRef;
import com.claimsai.identity.app.UserService;
import com.claimsai.identity.domain.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class ClaimQueryService {

    private final ClaimRepository claims;
    private final InfoRequestRepository infoRequests;
    private final ClaimNoteRepository notes;
    private final ClaimAccess access;
    private final UserService users;
    private final AuditService audit;
    private final ClaimFinancialsPort financials;

    public ClaimQueryService(ClaimRepository claims, InfoRequestRepository infoRequests, ClaimNoteRepository notes,
                             ClaimAccess access, UserService users, AuditService audit, ClaimFinancialsPort financials) {
        this.claims = claims;
        this.infoRequests = infoRequests;
        this.notes = notes;
        this.access = access;
        this.users = users;
        this.audit = audit;
        this.financials = financials;
    }

    /** Facts other modules' background work needs (no user: system access). */
    public record ClaimFacts(Long claimId, String claimNumber, String policyNumber, LocalDate lossDate,
                             String lossType, String description, BigDecimal estimatedLoss,
                             ClaimStatus status, Long claimantUserId) {
    }

    public ClaimFacts facts(Long claimId) {
        Claim c = claims.findById(claimId).orElseThrow(() ->
                new NotFoundException("CLAIM_NOT_FOUND", "Claim " + claimId + " not found"));
        return new ClaimFacts(c.getId(), c.getClaimNumber(), c.getPolicyNumber(), c.getLossDate(), c.getLossType().name(),
                c.getDescription(), c.getEstimatedLoss(), c.getStatus(), c.getClaimantUserId());
    }

    public Integer fraudScore(Long claimId) {
        return claims.findById(claimId).map(Claim::getFraudScore).orElse(null);
    }

    /** Claim numbers for ids (for lists owned by other modules). */
    public Map<Long, String> claimNumbers(Collection<Long> claimIds) {
        Map<Long, String> result = new HashMap<>();
        claims.findAllById(claimIds).forEach(c -> result.put(c.getId(), c.getClaimNumber()));
        return result;
    }

    /** Another claim on the same policy, for an SIU investigator. */
    public record PolicyClaim(Long claimId, String claimNumber, LocalDate lossDate, String lossType, ClaimStatus status,
                              Integer fraudScore) {
    }

    public List<PolicyClaim> otherClaimsOnPolicy(Long claimId) {
        Claim claim = claims.findById(claimId).orElseThrow();
        return claims.findByPolicyNumberAndIdNotOrderByLossDateDesc(claim.getPolicyNumber(), claimId).stream()
                .map(c -> new PolicyClaim(c.getId(), c.getClaimNumber(), c.getLossDate(), c.getLossType().name(),
                        c.getStatus(), c.getFraudScore()))
                .toList();
    }

    public Map<ClaimStatus, Long> countByStatus() {
        Map<ClaimStatus, Long> result = new EnumMap<>(ClaimStatus.class);
        for (ClaimStatus status : ClaimStatus.values()) {
            result.put(status, 0L);
        }
        claims.countByStatus().forEach(row -> result.put((ClaimStatus) row[0], (Long) row[1]));
        return result;
    }

    public long countUnassigned() {
        return claims.countUnassigned(EnumSet.of(ClaimStatus.OPEN, ClaimStatus.AWAITING_INFO, ClaimStatus.SIU_REVIEW));
    }

    public ClaimDetails details(Long claimId, CurrentUser user) {
        return details(access.loadVisible(claimId, user), user);
    }

    public ClaimDetails details(Claim claim, CurrentUser user) {
        UserRef adjuster = claim.getAssignedAdjusterId() == null ? null
                : users.refs(List.of(claim.getAssignedAdjusterId())).get(claim.getAssignedAdjusterId());
        InfoRequest openRequest = infoRequests.findByClaimIdAndStatus(claim.getId(), InfoRequest.Status.OPEN)
                .orElse(null);
        return new ClaimDetails(claim, adjuster, openRequest, ClaimAction.allowed(claim, user.id(), user.role()),
                financials.position(claim.getId()).totalPaid());
    }

    public record QueuePage(Page<Claim> claims, Map<Long, UserRef> adjusters) {
    }

    /** Staff work queue. An adjuster only ever sees their own; a supervisor sees all and may filter. */
    public QueuePage queue(ClaimStatus status, Segment segment, Long assigneeId, CurrentUser user, Pageable page) {
        Long assignee = user.role() == Role.ADJUSTER ? user.id() : assigneeId;
        Page<Claim> result = claims.findAll(ClaimSpecifications.queue(status, segment, assignee), page);
        List<Long> adjusterIds = result.getContent().stream().map(Claim::getAssignedAdjusterId)
                .filter(Objects::nonNull).distinct().toList();
        return new QueuePage(result, users.refs(adjusterIds));
    }

    public Page<Claim> filedBy(CurrentUser claimant, Pageable page) {
        return claims.findByClaimantUserId(claimant.id(), page);
    }

    /** Audit events and notes merged into one history, oldest first. */
    public List<TimelineEntry> timeline(Long claimId, CurrentUser user) {
        Claim claim = access.loadVisible(claimId, user);
        List<AuditEvent> events = audit.forClaim(claim.getId());
        List<ClaimNote> claimNotes = notes.findByClaimIdOrderByCreatedAtAscIdAsc(claim.getId());
        Map<Long, UserRef> authors = users.refs(claimNotes.stream().map(ClaimNote::getAuthorId).distinct().toList());

        List<TimelineEntry> entries = new ArrayList<>();
        events.forEach(e -> entries.add(new TimelineEntry("EVENT", e.getOccurredAt(), e.getActorName(), e.getAction(),
                e.getOldValue(), e.getNewValue(), e.getReason(), e.getCorrelationId())));
        claimNotes.forEach(n -> entries.add(new TimelineEntry("NOTE", n.getCreatedAt(),
                authors.get(n.getAuthorId()).username(), "NOTE_ADDED", null, null, n.getBody(), null)));
        // stable sort: entries at the same instant keep their order (events in id order, then notes)
        entries.sort(Comparator.comparing(TimelineEntry::at));
        return entries;
    }
}
