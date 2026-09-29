import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API, etagOf, idempotent, params } from './http-helpers';
import {
  Activity,
  Approval,
  CaseFile,
  Dashboard,
  Exposure,
  Job,
  Page,
  Payment,
  Recovery,
  ReserveOutcome,
  SiuCase,
} from './api.types';

/** Exposures, reserves, payments, recoveries, denials and the approval queue (ADR-0024). */
@Injectable({ providedIn: 'root' })
export class FinancialsApi {
  private http = inject(HttpClient);

  exposures(claimId: number): Observable<Exposure[]> {
    return this.http.get<Exposure[]>(`${API}/claims/${claimId}/exposures`);
  }

  createExposure(claimId: number, body: { type: string; claimantName: string; reserve: number | null; reason: string }): Observable<ReserveOutcome> {
    return this.http.post<ReserveOutcome>(`${API}/claims/${claimId}/exposures`, body);
  }

  changeReserve(exposure: Exposure, amount: number, reason: string): Observable<ReserveOutcome> {
    return this.http.put<ReserveOutcome>(`${API}/exposures/${exposure.id}/reserve`, { amount, reason }, {
      headers: { 'If-Match': etagOf(exposure.version) },
    });
  }

  closeExposure(exposure: Exposure, reason: string): Observable<Exposure> {
    return this.http.post<Exposure>(`${API}/exposures/${exposure.id}/close`, { reason }, {
      headers: { 'If-Match': etagOf(exposure.version) },
    });
  }

  payments(claimId: number): Observable<Payment[]> {
    return this.http.get<Payment[]>(`${API}/claims/${claimId}/payments`);
  }

  /** The key identifies this payment request; a retry with the same key can never pay twice. */
  requestPayment(exposureId: number, body: { amount: number; payeeName: string; reason: string }, idempotencyKey: string): Observable<Payment> {
    return this.http.post<Payment>(`${API}/exposures/${exposureId}/payments`, body, idempotent(idempotencyKey));
  }

  requestDenial(claimId: number, reason: string): Observable<Approval> {
    return this.http.post<Approval>(`${API}/claims/${claimId}/denial-requests`, { reason });
  }

  recoveries(claimId: number): Observable<Recovery[]> {
    return this.http.get<Recovery[]>(`${API}/claims/${claimId}/recoveries`);
  }

  recordRecovery(claimId: number, body: { amount: number; source: string; reference: string; receivedOn: string }): Observable<Recovery> {
    return this.http.post<Recovery>(`${API}/claims/${claimId}/recoveries`, body);
  }

  approvals(status = 'PENDING', page = 0, size = 50): Observable<Page<Approval>> {
    return this.http.get<Page<Approval>>(`${API}/approvals`, { params: params({ status, page, size }) });
  }

  approve(id: number, comment: string): Observable<Approval> {
    return this.http.post<Approval>(`${API}/approvals/${id}/approve`, { comment });
  }

  reject(id: number, reason: string): Observable<Approval> {
    return this.http.post<Approval>(`${API}/approvals/${id}/reject`, { reason });
  }
}

/** Activities, SIU, the supervisor dashboard and background-job operations. */
@Injectable({ providedIn: 'root' })
export class WorkApi {
  private http = inject(HttpClient);

  myActivities(overdueOnly = false, page = 0, size = 50): Observable<Page<Activity>> {
    return this.http.get<Page<Activity>>(`${API}/activities`, { params: params({ overdueOnly, page, size }) });
  }

  breachedActivities(page = 0, size = 50): Observable<Page<Activity>> {
    return this.http.get<Page<Activity>>(`${API}/activities/breached`, { params: params({ page, size }) });
  }

  claimActivities(claimId: number): Observable<Activity[]> {
    return this.http.get<Activity[]>(`${API}/claims/${claimId}/activities`);
  }

  completeActivity(id: number, note: string): Observable<Activity> {
    return this.http.post<Activity>(`${API}/activities/${id}/complete`, { note });
  }

  dashboard(): Observable<Dashboard> {
    return this.http.get<Dashboard>(`${API}/dashboard/supervisor`);
  }

  siuQueue(status = 'OPEN', page = 0, size = 50): Observable<Page<SiuCase>> {
    return this.http.get<Page<SiuCase>>(`${API}/siu/cases`, { params: params({ status, page, size }) });
  }

  siuCase(id: number): Observable<CaseFile> {
    return this.http.get<CaseFile>(`${API}/siu/cases/${id}`);
  }

  claimSiuCases(claimId: number): Observable<SiuCase[]> {
    return this.http.get<SiuCase[]>(`${API}/claims/${claimId}/siu-cases`);
  }

  recordSiuOutcome(id: number, outcome: 'CLEARED' | 'CONFIRMED', findings: string): Observable<SiuCase> {
    return this.http.post<SiuCase>(`${API}/siu/cases/${id}/outcome`, { outcome, findings });
  }

  jobs(status = 'FAILED', page = 0, size = 50): Observable<Page<Job>> {
    return this.http.get<Page<Job>>(`${API}/ops/jobs`, { params: params({ status, page, size }) });
  }

  retryJob(id: number): Observable<Job> {
    return this.http.post<Job>(`${API}/ops/jobs/${id}/retry`, null);
  }

  adjusters(): Observable<{ id: number; username: string; displayName: string }[]> {
    return this.http.get<{ id: number; username: string; displayName: string }[]>(`${API}/users`, {
      params: params({ role: 'ADJUSTER' }),
    });
  }
}
