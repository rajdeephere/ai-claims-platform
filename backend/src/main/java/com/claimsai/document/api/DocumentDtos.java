package com.claimsai.document.api;

import com.claimsai.document.app.DocumentService;
import com.claimsai.document.domain.Document;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record UploadUrlRequest(
            @Schema(example = "repair-estimate.pdf") @NotBlank @Size(max = 200) String fileName,
            @Schema(example = "application/pdf", description = "application/pdf, image/jpeg or image/png")
            @NotBlank @Size(max = 100) String contentType,
            @Schema(example = "245760", description = "Exact size in bytes; at most 10 MB")
            @Min(1) @Max(10L * 1024 * 1024) long sizeBytes,
            @NotNull Document.Category category) {

        DocumentService.UploadRequest toCommand() {
            return new DocumentService.UploadRequest(fileName, contentType, sizeBytes, category);
        }
    }

    @Schema(description = "PUT the file to uploadUrl with these headers before it expires, then call complete")
    public record UploadInstructions(String uploadUrl, String method, Map<String, String> headers, Instant expiresAt) {
    }

    // ---------- claimant ----------

    public record PortalDocumentView(Long id, Long claimId, String fileName, String contentType, Long sizeBytes,
                                     Document.Category category, Document.Status status, Instant uploadedAt) {

        static PortalDocumentView of(Document d) {
            return new PortalDocumentView(d.getId(), d.getClaimId(), d.getFileName(), d.getContentType(),
                    d.getSizeBytes(), d.getCategory(), d.getStatus(), d.getUploadedAt());
        }
    }

    public record PortalUploadResponse(PortalDocumentView document, UploadInstructions upload) {
    }

    @Schema(description = "duplicate = true: this exact file was already on the claim; 'document' is that one")
    public record PortalCompletion(PortalDocumentView document, boolean duplicate) {
    }

    // ---------- staff ----------

    public record StaffDocumentView(Long id, Long claimId, String fileName, String declaredContentType,
                                    String contentType, Long sizeBytes, String sha256, Document.Category category,
                                    String docType, Document.Status status, String rejectionReason, Long uploadedBy,
                                    boolean visibleToClaimant, Instant createdAt, Instant uploadedAt) {

        static StaffDocumentView of(Document d) {
            return new StaffDocumentView(d.getId(), d.getClaimId(), d.getFileName(), d.getDeclaredContentType(),
                    d.getContentType(), d.getSizeBytes(), d.getSha256(), d.getCategory(), d.getDocType(),
                    d.getStatus(), d.getRejectionReason(), d.getUploadedBy(), d.isVisibleToClaimant(),
                    d.getCreatedAt(), d.getUploadedAt());
        }
    }

    public record StaffUploadResponse(StaffDocumentView document, UploadInstructions upload) {
    }

    public record StaffCompletion(StaffDocumentView document, boolean duplicate) {
    }

    public record DownloadLinkResponse(String url, Instant expiresAt) {
    }

    static UploadInstructions instructions(DocumentService.UploadTicket ticket) {
        var u = ticket.upload();
        return new UploadInstructions(u.url().toString(), u.method(), u.headers(), u.expiresAt());
    }
}
