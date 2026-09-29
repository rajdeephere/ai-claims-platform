package com.claimsai.document.api;

import com.claimsai.common.openapi.DocumentedErrors;
import com.claimsai.document.api.DocumentDtos.DownloadLinkResponse;
import com.claimsai.document.api.DocumentDtos.StaffCompletion;
import com.claimsai.document.api.DocumentDtos.StaffDocumentView;
import com.claimsai.document.api.DocumentDtos.StaffUploadResponse;
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
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('ADJUSTER', 'SUPERVISOR', 'SIU')")
@Tag(name = "Documents", description = "Claim documents for staff")
public class StaffDocumentController {

    private final DocumentService documents;
    private final CurrentUserProvider currentUser;

    public StaffDocumentController(DocumentService documents, CurrentUserProvider currentUser) {
        this.documents = documents;
        this.currentUser = currentUser;
    }

    @PostMapping("/claims/{claimId}/documents")
    @Operation(operationId = "startDocumentUpload", summary = "Start an upload (staff documents are not shown to the claimant)")
    @ApiResponse(responseCode = "201", description = "Upload URL issued (valid 5 minutes)")
    @DocumentedErrors({404, 409, 422})
    public ResponseEntity<StaffUploadResponse> startUpload(@PathVariable Long claimId,
                                                           @Valid @RequestBody UploadUrlRequest request) {
        var ticket = documents.requestUpload(claimId, request.toCommand(), currentUser.get());
        return ResponseEntity.status(HttpStatus.CREATED).body(new StaffUploadResponse(
                StaffDocumentView.of(ticket.document()), DocumentDtos.instructions(ticket)));
    }

    @PostMapping("/documents/{id}/complete")
    @Operation(operationId = "completeDocumentUpload", summary = "Confirm the upload: the file is verified (real type, size, hash)")
    @DocumentedErrors({404, 409, 422})
    public StaffCompletion complete(@PathVariable Long id) {
        var completion = documents.complete(id, currentUser.get());
        return new StaffCompletion(StaffDocumentView.of(completion.document()), completion.duplicate());
    }

    @GetMapping("/claims/{claimId}/documents")
    @Operation(operationId = "listClaimDocuments", summary = "All documents on the claim, including rejected ones")
    @DocumentedErrors({404})
    public List<StaffDocumentView> list(@PathVariable Long claimId) {
        return documents.list(claimId, currentUser.get()).stream().map(StaffDocumentView::of).toList();
    }

    @GetMapping("/documents/{id}/download-url")
    @Operation(operationId = "getDocumentDownloadUrl", summary = "A download link valid for 5 minutes")
    @DocumentedErrors({404, 409})
    public DownloadLinkResponse downloadUrl(@PathVariable Long id) {
        var link = documents.downloadLink(id, currentUser.get());
        return new DownloadLinkResponse(link.url().toString(), link.expiresAt());
    }
}
