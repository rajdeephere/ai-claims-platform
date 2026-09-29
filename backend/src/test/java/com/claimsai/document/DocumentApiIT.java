package com.claimsai.document;

import com.claimsai.claim.api.ClaimDtos.ReasonRequest;
import com.claimsai.claim.api.ClaimDtos.TimelineEntryResponse;
import com.claimsai.common.error.ApiError;
import com.claimsai.document.api.DocumentDtos.DownloadLinkResponse;
import com.claimsai.document.api.DocumentDtos.PortalCompletion;
import com.claimsai.document.api.DocumentDtos.PortalDocumentView;
import com.claimsai.document.api.DocumentDtos.PortalUploadResponse;
import com.claimsai.document.api.DocumentDtos.StaffCompletion;
import com.claimsai.document.api.DocumentDtos.StaffDocumentView;
import com.claimsai.document.api.DocumentDtos.StaffUploadResponse;
import com.claimsai.document.api.DocumentDtos.UploadInstructions;
import com.claimsai.document.api.DocumentDtos.UploadUrlRequest;
import com.claimsai.document.app.AbandonedUploadSweeper;
import com.claimsai.document.domain.Document;
import com.claimsai.document.domain.DocumentStorage;
import com.claimsai.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.claimsai.document.FileRulesAndInspectionTest.EXE;
import static com.claimsai.document.FileRulesAndInspectionTest.PDF;
import static com.claimsai.document.FileRulesAndInspectionTest.PNG;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentApiIT extends IntegrationTest {

    private static final HttpClient BROWSER = HttpClient.newHttpClient();

    @Autowired
    private DocumentStorage storage;
    @Autowired
    private AbandonedUploadSweeper sweeper;

    /** What the browser does with the upload instructions. */
    private static int put(UploadInstructions upload, byte[] bytes) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                .method(upload.method(), HttpRequest.BodyPublishers.ofByteArray(bytes));
        upload.headers().forEach(request::header);
        return BROWSER.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static HttpResponse<byte[]> download(String url) throws Exception {
        return BROWSER.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private ResponseEntity<PortalUploadResponse> startPortalUpload(String claimant, Long claimId, String type, byte[] bytes) {
        return post(claimant, "/api/v1/portal/claims/" + claimId + "/documents",
                new UploadUrlRequest("estimate.pdf", type, bytes.length, Document.Category.REPAIR_ESTIMATE),
                PortalUploadResponse.class);
    }

    /** Request URL, PUT, complete: returns the completion response. */
    private <T> ResponseEntity<T> portalUpload(String claimant, Long claimId, byte[] bytes, String type, Class<T> result)
            throws Exception {
        PortalUploadResponse started = startPortalUpload(claimant, claimId, type, bytes).getBody();
        assertThat(put(started.upload(), bytes)).isEqualTo(200);
        return post(claimant, "/api/v1/portal/documents/" + started.document().id() + "/complete", null, result);
    }

    @Test
    void theClaimantUploadsDirectlyToStorageAndTheServerVerifiesWhatArrived() throws Exception {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");

        ResponseEntity<PortalUploadResponse> started = startPortalUpload("claimant1", claimId, "application/pdf", PDF);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(started.getBody().document().status()).isEqualTo(Document.Status.PENDING_UPLOAD);
        assertThat(started.getBody().upload().method()).isEqualTo("PUT");
        // header names are case-insensitive; the SDK gives them in lower case
        assertThat(started.getBody().upload().headers().keySet()).anyMatch(h -> h.equalsIgnoreCase("Content-Type"));
        assertThat(put(started.getBody().upload(), PDF)).isEqualTo(200);

        Long documentId = started.getBody().document().id();
        ResponseEntity<PortalCompletion> completed = post("claimant1", "/api/v1/portal/documents/" + documentId
                + "/complete", null, PortalCompletion.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody().duplicate()).isFalse();
        PortalDocumentView document = completed.getBody().document();
        assertThat(document.status()).isEqualTo(Document.Status.UPLOADED);
        assertThat(document.contentType()).isEqualTo("application/pdf");
        assertThat(document.sizeBytes()).isEqualTo(PDF.length);

        // staff see the hash and who can see it; the claimant's view has neither
        StaffDocumentView staffView = List.of(get("supervisor1", "/api/v1/claims/" + claimId + "/documents",
                StaffDocumentView[].class).getBody()).get(0);
        assertThat(staffView.sha256()).hasSize(64);
        assertThat(staffView.visibleToClaimant()).isTrue();
        assertThat(get("claimant1", "/api/v1/portal/claims/" + claimId + "/documents", String.class).getBody())
                .doesNotContain("sha256", "uploadedBy");

        String url = get("claimant1", "/api/v1/portal/documents/" + documentId + "/download-url",
                DownloadLinkResponse.class).getBody().url();
        HttpResponse<byte[]> file = download(url);
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(PDF);
        assertThat(file.headers().firstValue("Content-Disposition")).get().asString().startsWith("attachment;");

        assertThat(List.of(get("supervisor1", "/api/v1/claims/" + claimId + "/timeline", TimelineEntryResponse[].class)
                .getBody())).extracting(TimelineEntryResponse::action).contains("DOCUMENT_UPLOADED");
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_event WHERE event_type = 'DOCUMENT_UPLOADED' AND aggregate_id = ?")
                .param(documentId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void anExecutableDisguisedAsAPdfIsRejectedAndDeletedFromStorage() throws Exception {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");
        PortalUploadResponse started = startPortalUpload("claimant1", claimId, "application/pdf", EXE).getBody();
        put(started.upload(), EXE);

        ResponseEntity<ApiError> completed = post("claimant1", "/api/v1/portal/documents/" + started.document().id()
                + "/complete", null, ApiError.class);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(completed.getBody().code()).isEqualTo("UNSUPPORTED_FILE_TYPE");
        StaffDocumentView rejected = Arrays.stream(get("supervisor1", "/api/v1/claims/" + claimId + "/documents",
                StaffDocumentView[].class).getBody()).filter(d -> d.id().equals(started.document().id())).findFirst().orElseThrow();
        assertThat(rejected.status()).isEqualTo(Document.Status.REJECTED);
        assertThat(rejected.rejectionReason()).startsWith("content is ");
        String key = jdbc.sql("SELECT storage_key FROM document WHERE id = ?").param(started.document().id())
                .query(String.class).single();
        assertThat(storage.stat(key)).isEmpty();
        assertThat(get("claimant1", "/api/v1/portal/documents/" + started.document().id() + "/download-url",
                ApiError.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void theStoreRefusesAFileOfADifferentSizeThanTheOneSigned() throws Exception {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");
        PortalUploadResponse started = startPortalUpload("claimant1", claimId, "application/pdf", PDF).getBody();

        byte[] bigger = Arrays.copyOf(PDF, PDF.length + 1000);
        int status = put(started.upload(), bigger);

        assertThat(status).isBetween(400, 499);
        ResponseEntity<ApiError> completed = post("claimant1", "/api/v1/portal/documents/" + started.document().id()
                + "/complete", null, ApiError.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(completed.getBody().code()).isEqualTo("UPLOAD_NOT_FOUND");
    }

    @Test
    void theSameFileTwiceOnOneClaimIsStoredOnce() throws Exception {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");

        PortalCompletion first = portalUpload("claimant1", claimId, PNG, "image/png", PortalCompletion.class).getBody();
        PortalCompletion second = portalUpload("claimant1", claimId, PNG, "image/png", PortalCompletion.class).getBody();

        assertThat(second.duplicate()).isTrue();
        assertThat(second.document().id()).isEqualTo(first.document().id());
        assertThat(get("claimant1", "/api/v1/portal/claims/" + claimId + "/documents", PortalDocumentView[].class)
                .getBody()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE claim_id = ?").param(claimId).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void completingBeforeUploadingTellsTheClientWhatToDo() {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");
        PortalUploadResponse started = startPortalUpload("claimant1", claimId, "application/pdf", PDF).getBody();

        ResponseEntity<ApiError> early = post("claimant1", "/api/v1/portal/documents/" + started.document().id()
                + "/complete", null, ApiError.class);

        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody().code()).isEqualTo("UPLOAD_NOT_FOUND");
    }

    @Test
    void theDeclaredTypeAndSizeAreCheckedBeforeAnyUrlIsIssued() {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");

        ResponseEntity<ApiError> html = post("claimant1", "/api/v1/portal/claims/" + claimId + "/documents",
                new UploadUrlRequest("page.html", "text/html", 100, Document.Category.OTHER), ApiError.class);
        ResponseEntity<ApiError> huge = post("claimant1", "/api/v1/portal/claims/" + claimId + "/documents",
                new UploadUrlRequest("scan.pdf", "application/pdf", 50L * 1024 * 1024, Document.Category.OTHER),
                ApiError.class);

        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(html.getBody().code()).isEqualTo("UNSUPPORTED_FILE_TYPE");
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(huge.getBody().violations()).extracting(ApiError.FieldViolation::field).containsExactly("sizeBytes");
    }

    @Test
    void accessFollowsTheClaimAndStaffDocumentsStayInternal() throws Exception {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");
        String adjuster = assignedAdjuster(claimId);

        assertThat(startPortalUpload("claimant2", claimId, "application/pdf", PDF).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        StaffUploadResponse staffUpload = post(adjuster, "/api/v1/claims/" + claimId + "/documents",
                new UploadUrlRequest("surveillance.jpg", "image/png", PNG.length, Document.Category.OTHER),
                StaffUploadResponse.class).getBody();
        put(staffUpload.upload(), PNG);
        Long staffDoc = staffUpload.document().id();
        // only the uploader completes an upload
        assertThat(post("supervisor1", "/api/v1/documents/" + staffDoc + "/complete", null, ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        StaffCompletion done = post(adjuster, "/api/v1/documents/" + staffDoc + "/complete", null, StaffCompletion.class)
                .getBody();
        assertThat(done.document().visibleToClaimant()).isFalse();

        assertThat(get("claimant1", "/api/v1/portal/claims/" + claimId + "/documents", PortalDocumentView[].class)
                .getBody()).extracting(PortalDocumentView::id).doesNotContain(staffDoc);
        assertThat(get("claimant1", "/api/v1/portal/documents/" + staffDoc + "/download-url", ApiError.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        postIfMatch(adjuster, "/api/v1/claims/" + claimId + "/close", etag(adjuster, "/api/v1/claims/" + claimId),
                new ReasonRequest("done"), String.class);
        ResponseEntity<ApiError> afterClose = post("claimant1", "/api/v1/portal/claims/" + claimId + "/documents",
                new UploadUrlRequest("late.pdf", "application/pdf", PDF.length, Document.Category.OTHER), ApiError.class);
        assertThat(afterClose.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(afterClose.getBody().code()).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void uploadsThatAreNeverCompletedAreSweptAway() {
        Long claimId = portalClaim("claimant1", "POL-AUTO-1001");
        Long documentId = startPortalUpload("claimant1", claimId, "application/pdf", PDF).getBody().document().id();
        jdbc.sql("UPDATE document SET created_at = :old WHERE id = :id")
                .param("old", Timestamp.from(Instant.now().minus(Duration.ofDays(2)))).param("id", documentId).update();

        sweeper.sweep();

        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE id = ?").param(documentId).query(Long.class).single())
                .isZero();
    }
}
