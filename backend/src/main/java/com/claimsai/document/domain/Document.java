package com.claimsai.document.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Metadata of a file attached to a claim; the bytes live in object storage under {@link #getStorageKey()}.
 * Lifecycle: PENDING_UPLOAD (URL issued) -> UPLOADED (bytes verified) or REJECTED; PROCESSING / PROCESSED /
 * FAILED are the AI steps of phase 5.
 */
@Entity
@Table(name = "document")
public class Document {

    public enum Status { PENDING_UPLOAD, UPLOADED, PROCESSING, PROCESSED, FAILED, REJECTED }

    public enum Category { DAMAGE_PHOTO, REPAIR_ESTIMATE, POLICE_REPORT, INVOICE, MEDICAL_REPORT, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false, updatable = false)
    private Long claimId;

    @Column(name = "storage_key", nullable = false, updatable = false, length = 200)
    private String storageKey;

    @Column(name = "file_name", nullable = false, updatable = false, length = 200)
    private String fileName;

    @Column(name = "declared_content_type", nullable = false, updatable = false, length = 100)
    private String declaredContentType;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "declared_size_bytes", nullable = false, updatable = false)
    private long declaredSizeBytes;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Category category;

    @Column(name = "doc_type", length = 30)
    private String docType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Status status;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    /** Documents added by staff (e.g. an investigator's photos) are not shown to the claimant. */
    @Column(name = "visible_to_claimant", nullable = false, updatable = false)
    private boolean visibleToClaimant;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Version
    private long version;

    protected Document() {
        // for JPA
    }

    public static Document pending(Long claimId, String storageKey, String fileName, String declaredContentType,
                                   long declaredSizeBytes, Category category, Long uploadedBy,
                                   boolean visibleToClaimant, Instant now) {
        Document d = new Document();
        d.claimId = claimId;
        d.storageKey = storageKey;
        d.fileName = fileName;
        d.declaredContentType = declaredContentType;
        d.declaredSizeBytes = declaredSizeBytes;
        d.category = category;
        d.uploadedBy = uploadedBy;
        d.visibleToClaimant = visibleToClaimant;
        d.status = Status.PENDING_UPLOAD;
        d.createdAt = now;
        return d;
    }

    /** The bytes were checked: real type, size and hash are now known. */
    public void verified(String detectedContentType, long size, String sha256, Instant now) {
        requirePending();
        this.contentType = detectedContentType;
        this.sizeBytes = size;
        this.sha256 = sha256;
        this.status = Status.UPLOADED;
        this.uploadedAt = now;
    }

    public void reject(String reason, String detectedContentType, Long size) {
        requirePending();
        this.status = Status.REJECTED;
        this.rejectionReason = reason;
        this.contentType = detectedContentType;
        this.sizeBytes = size;
    }

    public boolean isPending() {
        return status == Status.PENDING_UPLOAD;
    }

    /** The file can be downloaded: its bytes were verified. */
    public boolean isAvailable() {
        return status != Status.PENDING_UPLOAD && status != Status.REJECTED;
    }

    private void requirePending() {
        if (status != Status.PENDING_UPLOAD) {
            throw new IllegalStateException("Document " + id + " is " + status + ", not PENDING_UPLOAD");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getClaimId() {
        return claimId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getDeclaredContentType() {
        return declaredContentType;
    }

    public String getContentType() {
        return contentType;
    }

    public long getDeclaredSizeBytes() {
        return declaredSizeBytes;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public Category getCategory() {
        return category;
    }

    public String getDocType() {
        return docType;
    }

    public Status getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public boolean isVisibleToClaimant() {
        return visibleToClaimant;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public long getVersion() {
        return version;
    }
}
