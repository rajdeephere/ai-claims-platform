import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API, Versioned, getVersioned, idempotent, params, postIfMatch, versioned } from './http-helpers';
import {
  AiAssessment,
  Body,
  ClaimStatus,
  Notification,
  Page,
  PortalClaim,
  PortalClaimSummary,
  StaffClaim,
  StaffClaimSummary,
  TimelineEntry,
} from './api.types';

/** The claimant's API (/portal): only their own claims, in the claimant view. */
@Injectable({ providedIn: 'root' })
export class PortalApi {
  private http = inject(HttpClient);
  private base = `${API}/portal`;

  myClaims(page = 0, size = 20): Observable<Page<PortalClaimSummary>> {
    return this.http.get<Page<PortalClaimSummary>>(`${this.base}/claims`, { params: params({ page, size }) });
  }

  claim(id: number): Observable<Versioned<PortalClaim>> {
    return getVersioned(this.http, `${this.base}/claims/${id}`);
  }

  /** Reuse the same key when retrying the same submission: the server returns the claim it already made. */
  report(fnol: Body<'FnolRequest'>, idempotencyKey: string): Observable<Versioned<PortalClaim>> {
    return versioned(this.http.post<PortalClaim>(`${this.base}/claims`, fnol, { observe: 'response', ...idempotent(idempotencyKey) }));
  }

  respond(id: number, etag: string, message: string): Observable<Versioned<PortalClaim>> {
    return postIfMatch(this.http, `${this.base}/claims/${id}/respond`, etag, { message });
  }

  withdraw(id: number, etag: string, reason: string): Observable<Versioned<PortalClaim>> {
    return postIfMatch(this.http, `${this.base}/claims/${id}/withdraw`, etag, { reason });
  }

  notifications(page = 0, size = 20): Observable<Page<Notification>> {
    return this.http.get<Page<Notification>>(`${this.base}/notifications`, { params: params({ page, size }) });
  }

  unreadCount(): Observable<{ unread: number }> {
    return this.http.get<{ unread: number }>(`${this.base}/notifications/unread-count`);
  }

  markRead(id: number): Observable<Notification> {
    return this.http.post<Notification>(`${this.base}/notifications/${id}/read`, null);
  }
}

export interface QueueFilter {
  status?: ClaimStatus | '';
  segment?: string;
  assigneeId?: number | null;
  page?: number;
  size?: number;
}

/** Staff claim handling. Every state change sends the ETag the user saw. */
@Injectable({ providedIn: 'root' })
export class StaffClaimsApi {
  private http = inject(HttpClient);
  private base = `${API}/claims`;

  queue(filter: QueueFilter): Observable<Page<StaffClaimSummary>> {
    return this.http.get<Page<StaffClaimSummary>>(this.base, {
      params: params({ ...filter, page: filter.page ?? 0, size: filter.size ?? 20 }),
    });
  }

  claim(id: number): Observable<Versioned<StaffClaim>> {
    return getVersioned(this.http, `${this.base}/${id}`);
  }

  timeline(id: number): Observable<TimelineEntry[]> {
    return this.http.get<TimelineEntry[]>(`${this.base}/${id}/timeline`);
  }

  requestInfo(id: number, etag: string, message: string): Observable<Versioned<StaffClaim>> {
    return postIfMatch(this.http, `${this.base}/${id}/request-info`, etag, { message });
  }

  cancelInfoRequest(id: number, etag: string, reason: string): Observable<Versioned<StaffClaim>> {
    return postIfMatch(this.http, `${this.base}/${id}/cancel-info-request`, etag, { reason });
  }

  referToSiu(id: number, etag: string, reason: string): Observable<Versioned<StaffClaim>> {
    return postIfMatch(this.http, `${this.base}/${id}/refer-siu`, etag, { reason });
  }

  close(id: number, etag: string, reason: string): Observable<Versioned<StaffClaim>> {
    return postIfMatch(this.http, `${this.base}/${id}/close`, etag, { reason });
  }

  reopen(id: number, etag: string, reason: string): Observable<Versioned<StaffClaim>> {
    return postIfMatch(this.http, `${this.base}/${id}/reopen`, etag, { reason });
  }

  reassign(id: number, etag: string, adjusterId: number, reason: string): Observable<Versioned<StaffClaim>> {
    return postIfMatch(this.http, `${this.base}/${id}/reassign`, etag, { adjusterId, reason });
  }

  addNote(id: number, body: string): Observable<unknown> {
    return this.http.post(`${this.base}/${id}/notes`, { body });
  }

  aiAssessments(id: number): Observable<AiAssessment[]> {
    return this.http.get<AiAssessment[]>(`${this.base}/${id}/ai-assessments`);
  }

  acceptAssessment(assessmentId: number): Observable<AiAssessment> {
    return this.http.post<AiAssessment>(`${API}/ai-assessments/${assessmentId}/accept`, null);
  }

  overrideAssessment(assessmentId: number, fields: Record<string, unknown>, reason: string): Observable<AiAssessment> {
    return this.http.post<AiAssessment>(`${API}/ai-assessments/${assessmentId}/override`, { fields, reason });
  }
}
