# ADR-0019: Documents: direct upload with presigned URLs, verified on completion

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 4

## Context

Claimants upload photos, repair estimates and police reports (up to 10 MB). The API runs in 512 MB on
Render's free tier: streaming files through it costs memory and connection time, and a slow upload holds a
request thread. Uploaded files can't be trusted: a program can be renamed `estimate.pdf`, a size can be
lied about, and a file name can carry path or header tricks.

## Decision

**Upload in three steps, bytes never through the API:**

1. `POST /claims/{id}/documents` with name, type, exact size, category. The API checks access (the claim's
   rules: 404 / 403 / 409 when closed) and the declared type (PDF, JPEG, PNG) and size (≤ 10 MB), creates
   a `PENDING_UPLOAD` row and returns a **presigned PUT URL** valid 5 minutes. Type and exact size are
   part of the signature, so the store itself refuses a different size (tested).
2. The browser PUTs the file to the store.
3. `POST /documents/{id}/complete` (uploader only). The API reads the object **once, outside any
   transaction**: Tika detects the real type from the magic bytes (no file name), and a SHA-256 digest is
   computed in the same pass, in constant memory. Then:
   - not PDF/JPEG/PNG → `REJECTED`, object deleted, 422 `UNSUPPORTED_FILE_TYPE`
   - same hash already on this claim → the new row is dropped and the existing document returned
     (`duplicate: true`); a partial unique index `(claim_id, sha256)` settles concurrent completions
   - otherwise `UPLOADED`, audit `DOCUMENT_UPLOADED`, outbox `DOCUMENT_UPLOADED` (phase 5 starts AI from it)

**Safety:** storage keys are `claims/{claimId}/{uuid}`, never user input; file names are sanitised for
display; downloads are presigned GETs (5 min) with `Content-Disposition: attachment`, so a file is saved,
never rendered on our origin. Documents uploaded by staff are hidden from the claimant.

**Clean-up:** uploads never completed are deleted (row and object) after 24 hours.

**Storage:** one `DocumentStorage` port, one S3 adapter (AWS SDK v2, path-style, checksums only when
required). Locally and in tests: **SeaweedFS** (Apache 2.0) in Docker. In the cloud: **Supabase Storage**
through its S3-compatible endpoint (private bucket, CORS allowing PUT from the UI's origin).

## Consequences

- ✅ The API never buffers a file; upload speed is the store's problem, not the 512 MB container's.
- ✅ The type is what the bytes are, not what the name says; duplicates are stored once.
- ✅ The same code runs on SeaweedFS, Supabase, R2, B2 or AWS S3.
- ⚠️ Completion reads the file back once (10 MB at most) to inspect and hash it.
- ⚠️ A crash between "duplicate detected" and "object deleted" can leave an orphaned object; it's harmless
  and rare. A storage-side lifecycle rule could remove unreferenced objects later.

## Alternatives considered

- **Multipart upload through the API:** simplest client, but memory and threads on the smallest server.
- **Client-computed checksum in the signed URL:** strong, but browsers don't compute SHA-256 of a file by
  default; the server-side hash is authoritative anyway.
- **MinIO locally:** the obvious choice until 2025, when MinIO stopped publishing free images (the Docker
  Hub and Quay repositories refuse pulls). SeaweedFS is a real S3 server that enforces signatures, so
  presigned-URL tests are meaningful; S3Mock (lighter) accepts any signature, and LocalStack is 1.2 GB and
  skips signature validation by default.
- **Office documents (DOCX):** need Tika's parser modules, too heavy for 512 MB; users export to PDF.
