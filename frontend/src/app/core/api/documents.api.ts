import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, switchMap } from 'rxjs';
import { API } from './http-helpers';
import { DocumentCategory, PortalDocument, StaffDocument, UploadInstructions } from './api.types';

interface Started<D> {
  document: D;
  upload: UploadInstructions;
}

interface Completed<D> {
  document: D;
  duplicate: boolean;
}

/**
 * Documents go straight from the browser to object storage (ADR-0019): ask the API for a presigned URL,
 * PUT the bytes there with exactly the headers it gave, then tell the API it's done so it can verify the
 * file. The API never proxies the bytes, and the storage URL never sees our bearer token.
 */
@Injectable({ providedIn: 'root' })
export class DocumentsApi {
  private http = inject(HttpClient);

  list(claimId: number, portal: boolean): Observable<(PortalDocument | StaffDocument)[]> {
    return this.http.get<(PortalDocument | StaffDocument)[]>(`${this.base(portal)}/claims/${claimId}/documents`);
  }

  upload(claimId: number, file: File, category: DocumentCategory, portal: boolean): Observable<Completed<PortalDocument | StaffDocument>> {
    const base = this.base(portal);
    return this.http
      .post<Started<PortalDocument | StaffDocument>>(`${base}/claims/${claimId}/documents`, {
        fileName: file.name,
        contentType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
        category,
      })
      .pipe(
        switchMap((started) =>
          this.http
            .request(started.upload.method, started.upload.uploadUrl, {
              body: file,
              headers: new HttpHeaders(started.upload.headers),
            })
            .pipe(map(() => started.document.id)),
        ),
        switchMap((documentId) =>
          this.http.post<Completed<PortalDocument | StaffDocument>>(`${base}/documents/${documentId}/complete`, null),
        ),
      );
  }

  downloadUrl(documentId: number, portal: boolean): Observable<string> {
    return this.http
      .get<{ url: string }>(`${this.base(portal)}/documents/${documentId}/download-url`)
      .pipe(map((r) => r.url));
  }

  private base(portal: boolean): string {
    return portal ? `${API}/portal` : API;
  }
}
