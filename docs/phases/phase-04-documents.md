# Phase 4: Documents

**Status:** ✅ Complete

## Goal

Claimants and staff attach files to claims without the files ever passing through the 512 MB API, and
nothing is stored that isn't what it claims to be.

## Delivered

| Item | Location |
|---|---|
| `document` table: metadata, declared vs detected type and size, SHA-256, status, visibility | `V5__documents.sql` |
| Storage port + S3 adapter (AWS SDK v2, path-style, checksums when required, presigned PUT/GET) | `document/domain/DocumentStorage`, `document/infra/S3DocumentStorage` |
| Content inspection: Tika type from magic bytes + SHA-256 in one pass, constant memory | `document/infra/TikaContentInspector` |
| Upload → PUT → complete flow; rejection (object deleted); duplicate detection; race-safe via partial unique index | `document/app/DocumentService` |
| File rules: PDF/JPEG/PNG, 10 MB, safe display names | `document/domain/FileRules` |
| Downloads: 5-minute presigned GET, `Content-Disposition: attachment` (RFC 6266) | `S3DocumentStorage` |
| Claimant vs staff documents; claim access rules reused; `UPLOAD_DOCUMENT` in `allowedActions` | `DocumentService`, `ClaimAction` |
| Abandoned uploads removed after 24 h | `document/app/AbandonedUploadSweeper` |
| Audit `DOCUMENT_UPLOADED` / `DOCUMENT_REJECTED`; outbox `DOCUMENT_UPLOADED` (for phase 5) | `DocumentService` |
| SeaweedFS in docker-compose and Testcontainers (same image, `chrislusf/seaweedfs:4.48`) | `docker-compose.yml`, `infra/seaweedfs/s3.json`, `TestcontainersConfiguration` |
| Explicit operationIds on all 31 endpoints + build check | all controllers, `PlatformApiIT` |
| ADRs 0019–0020; contract 1.3.0 | `docs/adr/` |

## Endpoints added

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/v1/portal/claims/{claimId}/documents` | CLAIMANT | start an upload: presigned PUT URL |
| POST | `/api/v1/portal/documents/{id}/complete` | CLAIMANT (uploader) | verify: UPLOADED / duplicate / 422 |
| GET | `/api/v1/portal/claims/{claimId}/documents` | CLAIMANT | documents on my claim (not staff-only ones) |
| GET | `/api/v1/portal/documents/{id}/download-url` | CLAIMANT | 5-minute download link |
| POST | `/api/v1/claims/{claimId}/documents` | staff | start an upload (hidden from the claimant) |
| POST | `/api/v1/documents/{id}/complete` | uploader | verify |
| GET | `/api/v1/claims/{claimId}/documents` | staff | all documents, including rejected |
| GET | `/api/v1/documents/{id}/download-url` | staff | 5-minute download link |

## Error codes

| Code | Status | When |
|---|---|---|
| `UNSUPPORTED_FILE_TYPE` | 422 | declared type not PDF/JPEG/PNG, or the bytes are something else (file rejected and deleted) |
| `FILE_TOO_LARGE` / `VALIDATION_FAILED` | 422 / 400 | over 10 MB |
| `UPLOAD_NOT_FOUND` | 409 | complete called before the PUT (or the PUT was refused) |
| `DOCUMENT_NOT_AVAILABLE` | 409 | download of a pending or rejected document |
| `INVALID_TRANSITION` | 409 | upload to a closed claim |
| `DOCUMENT_NOT_FOUND` | 404 | not visible (other claimant, staff-only document) |

## Verification

| Check | Result |
|---|---|
| Unit tests | ✅ 134 (+ Tika detection and hashing incl. 3 MB file, file-name sanitising, Content-Disposition) |
| Integration tests (Testcontainers PostgreSQL + SeaweedFS) | ✅ 66 (+ documents 8, operationId guard 1) |
| Coverage (merged) | 94.7% lines, 83.3% branches |
| Round trip: presign → PUT → complete → download returns identical bytes, as an attachment | ✅ |
| Executable declared as PDF → 422, REJECTED, object deleted | ✅ |
| PUT with more bytes than signed → refused by the store | ✅ |
| Same file twice → one document, `duplicate: true` | ✅ |
| Other claimant → 404; staff document hidden from claimant; closed claim → 409; only the uploader completes | ✅ |
| Abandoned upload swept | ✅ |
| Local smoke test against docker-compose SeaweedFS (bucket auto-created, curl PUT, download) | ✅ |

## Issues found and fixed

1. **Adding a controller renamed existing API operations (BUG-005).** springdoc numbers colliding
   operationIds (`list_1`, `list_2`) in scan order; a new `list()` shifted them. Found in the contract
   diff; fixed with explicit operationIds everywhere and a test that forbids generated ones (ADR-0020).
2. **MinIO images no longer available (BUG-006).** Both Docker Hub and Quay refuse pulls. Evaluated
   SeaweedFS, LocalStack and S3Mock; chose SeaweedFS (real signature checks). Two environment traps on the
   way: Git Bash rewrote `-dir=/data` into a Windows path (`MSYS_NO_PATHCONV=1`), and the healthcheck
   failed because `localhost` resolves to IPv6 inside the container (use `127.0.0.1`).

## Deferred

- AI processing of uploaded documents (phase 5, triggered by `DOCUMENT_UPLOADED`).
- Upload from the claimant counting as an answer to an information request: v1 keeps "respond" explicit.
- Virus scanning (ClamAV) and a storage lifecycle rule for orphaned objects.
