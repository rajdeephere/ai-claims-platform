import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { PortalApi } from '../../core/api/claims.api';
import { DocumentsApi } from '../../core/api/documents.api';
import { PortalClaim, PortalDocument } from '../../core/api/api.types';
import { Versioned } from '../../core/api/http-helpers';
import { apiError } from '../../core/http/api-errors';
import { ToastService } from '../../core/toast.service';
import { DocumentUploaderComponent } from '../../shared/document-uploader.component';
import { LabelPipe } from '../../shared/labels.pipe';
import { MoneyPipe } from '../../shared/money.pipe';
import { askReason } from '../../shared/reason-dialog.component';
import { SkeletonComponent } from '../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

/** The claimant's view: what they reported, what we need from them, their documents. Never internal data. */
@Component({
  selector: 'app-portal-claim',
  imports: [DatePipe, FormsModule, StatusBadgeComponent, SkeletonComponent, DocumentUploaderComponent, LabelPipe, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (claim(); as c) {
      <div class="flex flex-wrap items-center justify-between gap-4 mb-5">
        <div>
          <h1 class="text-[22px] font-bold text-primary mb-0.5 flex items-center gap-3">
            {{ c.claimNumber }} <app-status-badge [status]="c.status" />
          </h1>
          <p class="text-[13.5px] text-gray-500">{{ c.lossType | label }} on {{ c.lossDate | date: 'mediumDate' }} · policy {{ c.policyNumber }}</p>
        </div>
        @if (can('WITHDRAW')) {
          <button class="btn-secondary !text-destructive" (click)="withdraw()">
            <span class="material-icons text-[16px]">undo</span>Withdraw claim
          </button>
        }
      </div>

      @if (c.openInfoRequest; as request) {
        <div class="card p-5 mb-4 !border-amber-300 bg-amber-50/40">
          <p class="text-xs font-bold text-warning uppercase tracking-wide mb-1">Your adjuster needs something from you</p>
          <p class="text-sm text-gray-800 mb-3">{{ request.message }}</p>
          <p class="text-[11px] text-gray-500 mb-3">Asked {{ request.requestedAt | date: 'medium' }}. You can upload documents below as well.</p>
          @if (can('RESPOND')) {
            <textarea rows="3" class="input" [(ngModel)]="answer" placeholder="Your answer" maxlength="2000"></textarea>
            <div class="flex justify-end mt-2">
              <button class="btn-primary" [disabled]="!answer.trim() || busy()" (click)="respond()">Send answer</button>
            </div>
          }
        </div>
      }

      <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div class="card p-5 lg:col-span-1">
          <h3 class="text-sm font-bold text-primary mb-3">What you told us</h3>
          <dl class="text-[13px] space-y-2">
            <div><dt class="text-gray-500">Where</dt><dd class="text-gray-800">{{ c.lossLocation }}</dd></div>
            <div><dt class="text-gray-500">What happened</dt><dd class="text-gray-800 whitespace-pre-line">{{ c.description }}</dd></div>
            <div><dt class="text-gray-500">Your estimate</dt><dd class="text-gray-800">{{ c.estimatedLoss | money }}</dd></div>
            <div><dt class="text-gray-500">Reported</dt><dd class="text-gray-800">{{ c.submittedAt | date: 'medium' }}</dd></div>
            @if (c.amountPaid) {
              <div><dt class="text-gray-500">Paid to date</dt><dd class="text-success font-semibold">{{ c.amountPaid | money }}</dd></div>
            }
            @if (c.closedAt) {
              <div><dt class="text-gray-500">Closed</dt><dd class="text-gray-800">{{ c.closedAt | date: 'medium' }}</dd></div>
            }
          </dl>
        </div>

        <div class="card p-5 lg:col-span-2">
          <h3 class="text-sm font-bold text-primary mb-3">Documents</h3>
          @if (documents().length) {
            <ul class="divide-y divide-gray-100 mb-4">
              @for (d of documents(); track d.id) {
                <li class="flex items-center gap-3 py-2 text-[13px]">
                  <span class="material-icons text-[18px] text-gray-400">{{ d.contentType === 'application/pdf' ? 'picture_as_pdf' : 'image' }}</span>
                  <button class="flex-1 text-left truncate text-primary hover:underline" (click)="download(d)">{{ d.fileName }}</button>
                  <span class="text-gray-500">{{ d.category | label }}</span>
                  <app-status-badge [status]="d.status" />
                </li>
              }
            </ul>
          } @else {
            <p class="text-[13px] text-gray-500 mb-4">No documents yet.</p>
          }
          @if (can('UPLOAD_DOCUMENT')) {
            <app-document-uploader [claimId]="c.id" [portal]="true" (uploaded)="loadDocuments()" />
          }
        </div>
      </div>
    } @else {
      <div class="card"><app-skeleton [rows]="5" /></div>
    }
  `,
})
export class PortalClaimComponent implements OnInit {
  private api = inject(PortalApi);
  private documentsApi = inject(DocumentsApi);
  private toast = inject(ToastService);
  private dialog = inject(MatDialog);

  id = input.required<string>();

  private current = signal<Versioned<PortalClaim> | null>(null);
  claim = computed(() => this.current()?.value ?? null);
  documents = signal<PortalDocument[]>([]);
  busy = signal(false);
  answer = '';

  ngOnInit(): void {
    this.load();
    this.loadDocuments();
  }

  can(action: string): boolean {
    return this.claim()?.allowedActions.includes(action as never) ?? false;
  }

  load(): void {
    this.api.claim(+this.id()).subscribe({ next: (c) => this.current.set(c), error: (e) => this.toast.error(e) });
  }

  loadDocuments(): void {
    this.documentsApi.list(+this.id(), true).subscribe((d) => this.documents.set(d as PortalDocument[]));
  }

  respond(): void {
    const c = this.current()!;
    this.busy.set(true);
    this.api.respond(c.value.id, c.etag, this.answer.trim()).subscribe({
      next: (updated) => {
        this.current.set(updated);
        this.answer = '';
        this.busy.set(false);
        this.toast.success('Thank you. Your adjuster has your answer.');
      },
      error: (e) => this.failed(e),
    });
  }

  withdraw(): void {
    askReason(this.dialog, {
      title: 'Withdraw this claim?',
      message: 'We will close the claim and stop working on it. This cannot be undone from the portal.',
      label: 'Why are you withdrawing? (optional)',
      confirmText: 'Withdraw',
      danger: true,
      required: false,
    }).subscribe((reason) => {
      if (reason === undefined) {
        return;
      }
      const c = this.current()!;
      this.api.withdraw(c.value.id, c.etag, reason).subscribe({
        next: (updated) => {
          this.current.set(updated);
          this.toast.success('Your claim was withdrawn.');
        },
        error: (e) => this.failed(e),
      });
    });
  }

  download(d: PortalDocument): void {
    this.documentsApi.downloadUrl(d.id, true).subscribe({
      next: (url) => window.open(url, '_blank', 'noopener'),
      error: (e) => this.toast.error(e),
    });
  }

  private failed(e: unknown): void {
    this.busy.set(false);
    this.toast.error(e);
    if (apiError(e).status === 412) {
      this.load();   // someone changed the claim meanwhile: show the latest version
    }
  }
}
