import { DatePipe, DecimalPipe, KeyValuePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StaffClaimsApi } from '../../../core/api/claims.api';
import { DocumentsApi } from '../../../core/api/documents.api';
import { AiAssessment, StaffClaim, StaffDocument } from '../../../core/api/api.types';
import { ToastService } from '../../../core/toast.service';
import { DocumentUploaderComponent } from '../../../shared/document-uploader.component';
import { LabelPipe } from '../../../shared/labels.pipe';
import { MoneyPipe } from '../../../shared/money.pipe';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';

type Json = Record<string, unknown>;

/** Fields a person may correct (the server's override whitelist). */
const EDITABLE = ['docType', 'totalAmount', 'currency', 'issueDate', 'severity', 'costLow', 'costHigh'] as const;

/**
 * Documents and what the AI read from them (ADR-0021): the model suggests, a person accepts or corrects,
 * with a reason. What's shown is the effective value (the correction if there is one).
 */
@Component({
  selector: 'app-claim-documents',
  imports: [DatePipe, DecimalPipe, KeyValuePipe, FormsModule, StatusBadgeComponent, DocumentUploaderComponent, LabelPipe, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid grid-cols-1 xl:grid-cols-5 gap-4">
      <div class="card p-5 xl:col-span-2 self-start">
        <h3 class="text-sm font-bold text-primary mb-3">Documents</h3>
        @for (d of documents(); track d.id) {
          <div class="py-2.5 border-b border-gray-100 last:border-0 text-[13px]">
            <div class="flex items-center gap-2">
              <span class="material-icons text-[18px] text-gray-400">{{ d.contentType === 'application/pdf' ? 'picture_as_pdf' : 'image' }}</span>
              <button class="flex-1 text-left truncate text-primary hover:underline" (click)="download(d)">{{ d.fileName }}</button>
              <app-status-badge [status]="d.status" />
            </div>
            <p class="text-[11px] text-gray-400 mt-0.5 ml-7">
              {{ d.category | label }} · {{ (d.sizeBytes / 1024) | number: '1.0-0' }} KB · {{ d.createdAt | date: 'short' }}
              @if (!d.visibleToClaimant) { · staff only }
            </p>
            @if (d.rejectionReason) { <p class="text-[11px] text-destructive ml-7">{{ d.rejectionReason }}</p> }
          </div>
        } @empty {
          <p class="text-[13px] text-gray-500">No documents yet.</p>
        }
        @if (claim().allowedActions.includes('UPLOAD_DOCUMENT')) {
          <div class="mt-4"><app-document-uploader [claimId]="claim().id" (uploaded)="load()" /></div>
        }
      </div>

      <div class="xl:col-span-3 space-y-4">
        @for (a of assessments(); track a.id) {
          <div class="card p-5">
            <div class="flex items-center justify-between mb-2">
              <h3 class="text-sm font-bold text-primary">
                @if (a.kind === 'FRAUD_SCORE') { Fraud score } @else { {{ fileName(a.documentId) }} }
              </h3>
              <div class="flex items-center gap-2">
                <app-status-badge [status]="a.status === 'FAILED' ? 'FAILED' : a.reviewStatus" />
              </div>
            </div>
            <p class="text-[11px] text-gray-400 mb-3">
              {{ a.model ?? 'rules' }} · prompt {{ a.promptVersion ?? '—' }}
              @if (a.confidence !== null && a.confidence !== undefined) { · confidence {{ (a.confidence * 100) | number: '1.0-0' }}% }
              @if (a.latencyMs) { · {{ a.latencyMs }} ms } · {{ a.createdAt | date: 'short' }}
            </p>

            @if (a.status === 'FAILED') {
              <p class="text-[13px] text-destructive">{{ a.error }} This document needs a manual review.</p>
            } @else if (a.kind === 'FRAUD_SCORE') {
              <p class="text-[26px] font-bold">{{ effective(a)['score'] }}</p>
              @for (r of reasons(a); track $index) {
                <div class="flex justify-between text-[13px] py-1 border-b border-gray-100 last:border-0">
                  <span class="text-gray-700">{{ r['rule'] | label }} <span class="text-gray-400">· {{ r['detail'] }}</span></span>
                  <span class="font-semibold text-destructive">+{{ r['points'] }}</span>
                </div>
              } @empty { <p class="text-[13px] text-success">No fraud signals.</p> }
            } @else {
              <dl class="grid grid-cols-2 md:grid-cols-3 gap-x-4 gap-y-2 text-[13px]">
                <div><dt class="text-gray-500">Type</dt><dd class="text-gray-800">{{ str(effective(a)['docType']) | label }}</dd></div>
                @for (f of fields(a) | keyvalue; track f.key) {
                  @if (f.value !== null && f.value !== undefined && f.value !== '') {
                    <div><dt class="text-gray-500">{{ f.key | label }}</dt>
                      <dd class="text-gray-800">{{ f.key === 'totalAmount' ? (num(f.value) | money) : f.value }}</dd></div>
                  }
                }
                @if (damage(a); as dmg) {
                  <div><dt class="text-gray-500">Severity</dt><dd class="text-gray-800">{{ str(dmg['severity']) | label }}</dd></div>
                  <div><dt class="text-gray-500">Cost range</dt><dd class="text-gray-800">{{ num(dmg['costLow']) | money }} – {{ num(dmg['costHigh']) | money }}</dd></div>
                }
              </dl>
              @for (s of signals(a); track $index) {
                <div class="mt-2 flex gap-2 p-2 rounded bg-[#FEE2E2] text-[#991B1B] text-[12px]">
                  <span class="material-icons text-[16px]">warning</span><b>{{ s['code'] | label }}</b> {{ s['detail'] }}
                </div>
              }
              @if (a.overrideReason) {
                <p class="mt-3 text-[12px] text-[#5B21B6]"><b>Corrected:</b> {{ a.overrideReason }}</p>
              }

              @if (canReview() && a.reviewStatus === 'PENDING_REVIEW') {
                @if (editing() === a.id) {
                  <div class="mt-4 p-3 rounded-lg bg-gray-50 border border-gray-200">
                    <div class="grid grid-cols-2 md:grid-cols-4 gap-2">
                      @for (key of editable; track key) {
                        <div>
                          <label class="label">{{ key | label }}</label>
                          <input class="input" [(ngModel)]="draft[key]" />
                        </div>
                      }
                    </div>
                    <label class="label mt-3">Why the correction</label>
                    <textarea rows="2" class="input" [(ngModel)]="overrideReason"></textarea>
                    <div class="flex justify-end gap-2 mt-2">
                      <button class="btn-secondary" (click)="editing.set(null)">Cancel</button>
                      <button class="btn-accent" [disabled]="!overrideReason.trim()" (click)="saveOverride(a)">Save correction</button>
                    </div>
                  </div>
                } @else {
                  <div class="flex justify-end gap-2 mt-4">
                    <button class="btn-secondary" (click)="startEdit(a)"><span class="material-icons text-[16px]">edit</span>Correct</button>
                    <button class="btn-accent" (click)="accept(a)"><span class="material-icons text-[16px]">done</span>Accept</button>
                  </div>
                }
              }
            }
          </div>
        } @empty {
          <div class="card p-5 text-[13px] text-gray-500">No AI results yet. Documents are assessed in the background after upload.</div>
        }
      </div>
    </div>
  `,
})
export class ClaimDocumentsComponent implements OnInit {
  private api = inject(StaffClaimsApi);
  private documentsApi = inject(DocumentsApi);
  private toast = inject(ToastService);

  claim = input.required<StaffClaim>();

  documents = signal<StaffDocument[]>([]);
  assessments = signal<AiAssessment[]>([]);
  editing = signal<number | null>(null);
  canReview = computed(() => this.claim().allowedActions.includes('REVIEW_AI'));

  editable = EDITABLE;
  draft: Record<string, string> = {};
  private original: Record<string, string> = {};
  overrideReason = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.documentsApi.list(this.claim().id, false).subscribe((d) => this.documents.set(d as StaffDocument[]));
    this.api.aiAssessments(this.claim().id).subscribe((a) => this.assessments.set(a));
  }

  effective(a: AiAssessment): Json {
    return ((a.overrideOutput ?? a.output) ?? {}) as Json;
  }

  fields(a: AiAssessment): Json {
    return (this.effective(a)['fields'] ?? {}) as Json;
  }

  damage(a: AiAssessment): Json | null {
    return (this.effective(a)['damage'] as Json | null) ?? null;
  }

  signals(a: AiAssessment): Json[] {
    return (this.effective(a)['riskSignals'] ?? []) as Json[];
  }

  reasons(a: AiAssessment): Json[] {
    return (this.effective(a)['reasons'] ?? []) as Json[];
  }

  fileName(documentId: number | null): string {
    return this.documents().find((d) => d.id === documentId)?.fileName ?? 'Document ' + documentId;
  }

  str(v: unknown): string {
    return v === null || v === undefined ? '' : String(v);
  }

  num(v: unknown): number | null {
    return v === null || v === undefined || v === '' ? null : Number(v);
  }

  accept(a: AiAssessment): void {
    this.api.acceptAssessment(a.id).subscribe({
      next: () => {
        this.toast.success('Accepted');
        this.load();
      },
      error: (e) => this.toast.error(e),
    });
  }

  startEdit(a: AiAssessment): void {
    const e = this.effective(a);
    const f = this.fields(a);
    const d = this.damage(a) ?? {};
    this.draft = {
      docType: this.str(e['docType']),
      totalAmount: this.str(f['totalAmount']),
      currency: this.str(f['currency']),
      issueDate: this.str(f['issueDate']),
      severity: this.str(d['severity']),
      costLow: this.str(d['costLow']),
      costHigh: this.str(d['costHigh']),
    };
    this.original = { ...this.draft };
    this.overrideReason = '';
    this.editing.set(a.id);
  }

  /** Only the fields the person changed are sent; the server validates them like the AI's answer. */
  saveOverride(a: AiAssessment): void {
    const numeric = new Set(['totalAmount', 'costLow', 'costHigh']);
    const changes: Record<string, unknown> = {};
    for (const key of EDITABLE) {
      if (this.draft[key] !== this.original[key]) {
        const value = this.draft[key].trim();
        changes[key] = value === '' ? null : numeric.has(key) ? Number(value) : value;
      }
    }
    if (Object.keys(changes).length === 0) {
      this.toast.info('Nothing was changed');
      return;
    }
    this.api.overrideAssessment(a.id, changes, this.overrideReason.trim()).subscribe({
      next: () => {
        this.editing.set(null);
        this.toast.success('Correction saved; the fraud score is recalculated.');
        this.load();
      },
      error: (e) => this.toast.error(e),
    });
  }

  download(d: StaffDocument): void {
    this.documentsApi.downloadUrl(d.id, false).subscribe({
      next: (url) => window.open(url, '_blank', 'noopener'),
      error: (e) => this.toast.error(e),
    });
  }
}
