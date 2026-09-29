package com.claimsai.document.app;

import com.claimsai.document.domain.Document;
import com.claimsai.document.domain.DocumentStorage;
import com.claimsai.document.infra.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.Optional;

/**
 * The document module's side of AI processing (called by the ai module's jobs, never by users): facts about
 * a document, its bytes, and its processing status. The ai module never touches document tables directly.
 */
@Service
public class DocumentProcessing {

    private final DocumentRepository documents;
    private final DocumentStorage storage;

    public DocumentProcessing(DocumentRepository documents, DocumentStorage storage) {
        this.documents = documents;
        this.storage = storage;
    }

    public record DocumentFacts(Long documentId, Long claimId, String storageKey, String contentType, String sha256,
                                Document.Category category, Document.Status status, String fileName) {
    }

    @Transactional(readOnly = true)
    public Optional<DocumentFacts> facts(Long documentId) {
        return documents.findById(documentId).map(d -> new DocumentFacts(d.getId(), d.getClaimId(), d.getStorageKey(),
                d.getContentType(), d.getSha256(), d.getCategory(), d.getStatus(), d.getFileName()));
    }

    /** The file's bytes (at most 10 MB, checked at upload). Not transactional: it's storage I/O. */
    public byte[] content(DocumentFacts document) {
        try (InputStream in = storage.open(document.storageKey())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read document " + document.documentId(), e);
        }
    }

    @Transactional
    public void markProcessing(Long documentId) {
        documents.findById(documentId).ifPresent(Document::startProcessing);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markProcessed(Long documentId, String docType) {
        documents.findById(documentId).ifPresent(d -> d.processed(docType));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markFailed(Long documentId) {
        documents.findById(documentId).ifPresent(Document::processingFailed);
    }

    @Transactional(readOnly = true)
    public boolean hasDocumentsAwaitingAssessment(Long claimId) {
        return documents.existsByClaimIdAndStatusIn(claimId, EnumSet.of(Document.Status.UPLOADED, Document.Status.PROCESSING));
    }

    @Transactional(readOnly = true)
    public long duplicatesOnOtherClaims(Long claimId) {
        return documents.countDuplicatesOnOtherClaims(claimId);
    }
}
