import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../environments/environment';

export const API = environment.apiUrl + '/api/v1';

/** A resource with the ETag it was read at: commands on it send the ETag back as If-Match (ADR-0012). */
export interface Versioned<T> {
  value: T;
  etag: string;
}

export function versioned<T>(response$: Observable<HttpResponse<T>>): Observable<Versioned<T>> {
  return response$.pipe(map((r) => ({ value: r.body as T, etag: r.headers.get('ETag') ?? '' })));
}

export function getVersioned<T>(http: HttpClient, url: string): Observable<Versioned<T>> {
  return versioned(http.get<T>(url, { observe: 'response' }));
}

export function postIfMatch<T>(http: HttpClient, url: string, etag: string, body: unknown): Observable<Versioned<T>> {
  return versioned(http.post<T>(url, body, { observe: 'response', headers: new HttpHeaders({ 'If-Match': etag }) }));
}

/** An exposure's ETag is its version, quoted. */
export const etagOf = (version: number): string => `"${version}"`;

export function params(values: Record<string, string | number | boolean | null | undefined>): HttpParams {
  let p = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== undefined && value !== '') {
      p = p.set(key, String(value));
    }
  }
  return p;
}

export const idempotent = (key: string) => ({ headers: new HttpHeaders({ 'Idempotency-Key': key }) });
