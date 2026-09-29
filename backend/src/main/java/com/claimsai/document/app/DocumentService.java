package com.claimsai.document.app;

import com.claimsai.audit.app.AuditActor;
import com.claimsai.audit.app.AuditService;
import com.claimsai.claim.app.ClaimAccess;
import com.claimsai.claim.domain.Claim;
import com.claimsai.claim.domain.ClaimAction;
import com.claimsai.claim.domain.InvalidTransitionException;
import com.claimsai.common.error.BusinessRuleException;
import com.claimsai.common.error.ConflictException;
import com.claimsai.common.error.NotFoundException;
import com.claimsai.document.domain.ContentInspector;
import com.claimsai.document.domain.ContentInspector.Inspection;
import com.claimsai.document.domain.Document;
import com.claimsai.document.domain.DocumentEvents;
import com.claimsai.document.domain.DocumentStorage;
import com.claimsai.document.domain.DocumentStorage.PresignedUpload;
import com.claimsai.document.domain.FileRules;
import com.claimsai.document.infra.DocumentRepository;
import com.claimsai.identity.app.CurrentUser;
import com.claimsai.identity.domain.Role;
import com.claimsai.platform.outbox.app.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Documents on a claim (ADR-0019). The bytes go straight from the browser to object storage; the API only
 * issues URLs and then verifies what arrived:
 * <ol>
 *   <li>{@link #requestUpload}: checks access and the declared type and size, creates a PENDING_UPLOAD
 *       row and a presigned PUT URL (5 min) bound to that type and exact size</li>
 *   <li>the browser PUTs the file</li>
 *   <li>{@link #complete}: reads the object back once (outside any transaction), detects its real type
 *       from its bytes and hashes it; then UPLOADED, REJECTED (object deleted), or "duplicate" (the same
 *       file is already on this claim: the existing document is returned)</li>
 * </ol>
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final EnumSet<Document.Status> VERIFIED = EnumSet.of(Document.Status.UPLOADED,
            Document.Status.PROCESSING, Document.Status.PROCESSED, Document.Status.FAILED);

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final ContentInspector inspector;
    private final ClaimAccess claimAccess;
    private final AuditService audit;
    private final OutboxService outbox;
    private final StorageProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DocumentService(DocumentRepository documents, DocumentStorage storage, ContentInspector inspector,
                           ClaimAccess claimAccess, AuditService audit, OutboxService outbox,
                           StorageProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.documents = documents;
        this.storage = storage;
        this.inspector = inspector;
        this.claimAccess = claimAccess;
        this.audit = audit;
        this.outbox = outbox;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public record UploadRequest(String fileName, String contentType, long sizeBytes, Document.Category category) {
    }

    public record UploadTicket(Document document, PresignedUpload upload) {
    }

    public record Completion(Document document, boolean duplicate) {
    }

    public record DownloadLink(URI url, Instant expiresAt) {
    }

    // ---------- 1. issue the upload URL ----------

    @Transactional
    public UploadTicket requestUpload(Long claimId, UploadRequest request, CurrentUser user) {
        Claim claim = claimAccess.loadVisible(claimId, user);
        claimAccess.requirePermission(ClaimAction.UPLOAD_DOCUMENT, claim, user);
        if (!ClaimAction.UPLOAD_DOCUMENT.availableIn(claim.getStatus())) {
            throw new InvalidTransitionException("upload a document to", claim.getStatus());
        }
        String contentType = request.contentType().toLowerCase(Locale.ROOT).strip();
        if (!FileRules.isAllowedType(contentType)) {
            throw new BusinessRuleException("UNSUPPORTED_FILE_TYPE",
                    "Only PDF, JPEG and PNG files can be uploaded");
        }
        if (request.sizeBytes() > FileRules.MAX_SIZE_BYTES) {
            throw new BusinessRuleException("FILE_TOO_LARGE", "Files can be at most 10 MB");
        }
        // the key never contains anything the user typed
        String key = "claims/" + claimId + "/" + UUID.randomUUID();
        Document document = documents.save(Document.pending(claimId, key, FileRules.sanitizeFileName(request.fileName()),
                contentType, request.sizeBytes(), request.category(), user.id(), user.role() == Role.CLAIMANT,
                clock.instant()));
        // signing is local computation, no call to the store
        PresignedUpload upload = storage.presignUpload(key, contentType, request.sizeBytes(), properties.uploadUrlTtl());
        return new UploadTicket(document, upload);
    }

    // ---------- 3. verify what arrived ----------

    /** Not @Transactional: reading the object is I/O on another system and must not hold a transaction. */
    public Completion complete(Long documentId, CurrentUser user) {
        Document document = transaction.execute(status -> loadVisible(documentId, user));
        if (!document.getUploadedBy().equals(user.id())) {
            throw new AccessDeniedException("Only the uploader can complete an upload");
        }
        if (document.getStatus() == Document.Status.REJECTED) {
            throw rejected(document.getRejectionReason());
        }
        if (!document.isPending()) {
            return new Completion(document, false);   // completing twice is harmless
        }

        long storedSize = storage.stat(document.getStorageKey())
                .orElseThrow(() -> new ConflictException("UPLOAD_NOT_FOUND",
                        "The file hasn't been uploaded yet: PUT it to the upload URL first"))
                .sizeBytes();
        if (storedSize > FileRules.MAX_SIZE_BYTES || storedSize != document.getDeclaredSizeBytes()) {
            reject(document, "size " + storedSize + " bytes differs from the declared " + document.getDeclaredSizeBytes(),
                    null, storedSize, user);
            throw rejected("the uploaded size differs from the declared size");
        }
        Inspection inspection = inspect(document.getStorageKey());
        if (!FileRules.isAllowedType(inspection.contentType())) {
            reject(document, "content is " + inspection.contentType(), inspection.contentType(), inspection.sizeBytes(), user);
            throw rejected("the file content is " + inspection.contentType() + ", not a PDF, JPEG or PNG");
        }

        Completion completion;
        try {
            completion = transaction.execute(status -> applyVerified(documentId, inspection, user));
        } catch (DataIntegrityViolationException sameFileAtTheSameTime) {
            // two uploads of the same file on this claim completed at once; the unique index picked one
            completion = transaction.execute(status -> discardAsDuplicate(documentId, inspection)
                    .orElseThrow(() -> sameFileAtTheSameTime));
        }
        if (completion.duplicate()) {
            storage.delete(document.getStorageKey());
        }
        return completion;
    }

    private Completion applyVerified(Long documentId, Inspection inspection, CurrentUser user) {
        Optional<Completion> duplicate = discardAsDuplicate(documentId, inspection);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        Document document = documents.findById(documentId).orElseThrow();
        if (!document.isPending()) {
            return new Completion(document, false);
        }
        document.verified(inspection.contentType(), inspection.sizeBytes(), inspection.sha256(), clock.instant());
        documents.flush();   // hit the (claim, sha256) unique index now, inside this try
        audit.record("DOCUMENT", document.getId(), document.getClaimId(), "DOCUMENT_UPLOADED",
                new AuditActor(user.id(), user.username()), null, Map.of(
                        "documentId", document.getId(), "fileName", document.getFileName(),
                        "contentType", inspection.contentType(), "sizeBytes", inspection.sizeBytes(),
                        "category", document.getCategory().name()), null);
        outbox.append(DocumentEvents.AGGREGATE, document.getId(), DocumentEvents.DOCUMENT_UPLOADED, Map.of(
                "documentId", document.getId(), "claimId", document.getClaimId(),
                "contentType", inspection.contentType(), "category", document.getCategory().name()));
        return new Completion(document, false);
    }

    /** The same bytes are already on this claim: drop the new row, answer with the existing document. */
    private Optional<Completion> discardAsDuplicate(Long documentId, Inspection inspection) {
        Document document = documents.findById(documentId).orElseThrow();
        return documents.findFirstByClaimIdAndSha256AndStatusIn(document.getClaimId(), inspection.sha256(), VERIFIED)
                .filter(existing -> !existing.getId().equals(documentId))
                .map(existing -> {
                    documents.delete(document);
                    return new Completion(existing, true);
                });
    }

    private void reject(Document document, String reason, String detectedType, Long size, CurrentUser user) {
        transaction.executeWithoutResult(status -> {
            Document current = documents.findById(document.getId()).orElseThrow();
            if (current.isPending()) {
                current.reject(reason, detectedType, size);
                audit.record("DOCUMENT", current.getId(), current.getClaimId(), "DOCUMENT_REJECTED",
                        new AuditActor(user.id(), user.username()), null,
                        Map.of("documentId", current.getId(), "fileName", current.getFileName()), reason);
            }
        });
        storage.delete(document.getStorageKey());   // never keep bytes we refused
        log.info("Rejected document {} on claim {}: {}", document.getId(), document.getClaimId(), reason);
    }

    private Inspection inspect(String key) {
        try (InputStream content = storage.open(key)) {
            return inspector.inspect(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded object " + key, e);
        }
    }

    private static BusinessRuleException rejected(String reason) {
        return new BusinessRuleException("UNSUPPORTED_FILE_TYPE", "File rejected: " + reason);
    }

    // ---------- reading ----------

    @Transactional(readOnly = true)
    public List<Document> list(Long claimId, CurrentUser user) {
        Claim claim = claimAccess.loadVisible(claimId, user);
        return documents.findByClaimIdOrderByCreatedAtAscIdAsc(claim.getId()).stream()
                .filter(d -> d.getStatus() != Document.Status.PENDING_UPLOAD)
                .filter(d -> user.role() != Role.CLAIMANT || d.isVisibleToClaimant())
                .toList();
    }

    @Transactional(readOnly = true)
    public DownloadLink downloadLink(Long documentId, CurrentUser user) {
        Document document = loadVisible(documentId, user);
        if (!document.isAvailable()) {
            throw new ConflictException("DOCUMENT_NOT_AVAILABLE", "The document is " + document.getStatus());
        }
        URI url = storage.presignDownload(document.getStorageKey(), document.getFileName(), document.getContentType(),
                properties.downloadUrlTtl());
        return new DownloadLink(url, clock.instant().plus(properties.downloadUrlTtl()));
    }

    /** Visible document: its claim is visible to the user, and claimants only see their own side's files. */
    private Document loadVisible(Long documentId, CurrentUser user) {
        NotFoundException notFound = new NotFoundException("DOCUMENT_NOT_FOUND", "Document " + documentId + " not found");
        Document document = documents.findById(documentId).orElseThrow(() -> notFound);
        try {
            claimAccess.loadVisible(document.getClaimId(), user);
        } catch (NotFoundException hidden) {
            throw notFound;
        }
        if (user.role() == Role.CLAIMANT && !document.isVisibleToClaimant()) {
            throw notFound;
        }
        return document;
    }
}
