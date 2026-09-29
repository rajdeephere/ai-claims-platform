package com.claimsai.document.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.document.api.DocumentDtos.DownloadLinkResponse;
import com.claimsai.document.api.DocumentDtos.PortalCompletion;
import com.claimsai.document.api.DocumentDtos.PortalDocumentView;
import com.claimsai.document.api.DocumentDtos.PortalUploadResponse;
import com.claimsai.document.api.DocumentDtos.UploadUrlRequest;
import com.claimsai.document.app.DocumentService;
import com.claimsai.identity.app.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/portal")
@PreAuthorize("hasRole('CLAIMANT')")
@Tag(name = "Portal: documents", description = "Upload and download documents for my claims")
public class PortalDocumentController {

    private final DocumentService documents;
    private final CurrentUserProvider currentUser;

    public PortalDocumentController(DocumentService documents, CurrentUserProvider currentUser) {
        this.documents = documents;
        this.currentUser = currentUser;
    }

    @PostMapping("/claims/{claimId}/documents")
    @Operation(operationId = "startMyDocumentUpload", summary = "Start an upload: get a presigned URL",
            description = "PUT the file to upload.uploadUrl with upload.headers (the size must match exactly), "
                    + "then call POST /portal/documents/{id}/complete.")
    @ApiResponse(responseCode = "201", description = "Upload URL issued (valid 5 minutes)")
    @DocumentedErrors({404, 409, 422})
    public ResponseEntity<PortalUploadResponse> startUpload(@PathVariable Long claimId,
                                                            @Valid @RequestBody UploadUrlRequest request) {
        var ticket = documents.requestUpload(claimId, request.toCommand(), currentUser.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(new PortalUploadResponse(
                PortalDocumentView.of(ticket.document()), DocumentDtos.instructions(ticket)));
    }

    @PostMapping("/documents/{id}/complete")
    @Operation(operationId = "completeMyDocumentUpload", summary = "Confirm the upload: the file is verified (real type, size, hash)",
            description = "Rejected files (e.g. an executable renamed to .pdf) are deleted: 422. Uploading the same "
                    + "file twice returns the first document with duplicate = true.")
    @DocumentedErrors({404, 409, 422})
    public PortalCompletion complete(@PathVariable Long id) {
        var completion = documents.complete(id, currentUser.get());
        return new PortalCompletion(PortalDocumentView.of(completion.document()), completion.duplicate());
    }

    @GetMapping("/claims/{claimId}/documents")
    @Operation(operationId = "listMyClaimDocuments", summary = "Documents on my claim")
    @DocumentedErrors({404})
    public List<PortalDocumentView> list(@PathVariable Long claimId) {
        return documents.list(claimId, currentUser.get()).stream().map(PortalDocumentView::of).toList();
    }

    @GetMapping("/documents/{id}/download-url")
    @Operation(operationId = "getMyDocumentDownloadUrl", summary = "A download link valid for 5 minutes")
    @DocumentedErrors({404, 409})
    public DownloadLinkResponse downloadUrl(@PathVariable Long id) {
        var link = documents.downloadLink(id, currentUser.get());
        return new DownloadLinkResponse(link.url().toString(), link.expiresAt());
    }
}
