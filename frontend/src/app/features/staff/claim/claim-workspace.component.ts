import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable } from 'rxjs';
import { StaffClaimsApi } from '../../../core/api/claims.api';
import { FinancialsApi, WorkApi } from '../../../core/api/work.api';
import { ClaimAction, SiuCase, StaffClaim } from '../../../core/api/api.types';
import { Versioned } from '../../../core/api/http-helpers';
import { AuthService } from '../../../core/auth/auth.service';
import { apiError } from '../../../core/http/api-errors';
import { ToastService } from '../../../core/toast.service';
import { LabelPipe } from '../../../shared/labels.pipe';
import { MoneyPipe } from '../../../shared/money.pipe';
import { ReasonDialogData, askReason } from '../../../shared/reason-dialog.component';
import { SkeletonComponent } from '../../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';
import { ClaimActivitiesComponent } from './claim-activities.component';
import { ClaimDocumentsComponent } from './claim-documents.component';
import { ClaimFinancialsComponent } from './claim-financials.component';
import { ClaimTimelineComponent } from './claim-timeline.component';
import { ReassignDialogComponent, ReassignResult } from './reassign-dialog.component';

type Tab = 'overview' | 'documents' | 'financials' | 'activities' | 'timeline';

interface CommandButton {
  action: ClaimAction;
  label: string;
  icon: string;
  style: string;
}

/** Buttons appear only for actions the server lists in allowedActions: the UI never re-implements the rules. */
const COMMANDS: CommandButton[] = [
  { action: 'REQUEST_INFO', label: 'Ask the claimant', icon: 'help_outline', style: 'btn-secondary' },
  { action: 'CANCEL_INFO_REQUEST', label: 'Withdraw question', icon: 'cancel', style: 'btn-secondary' },
  { action: 'REFER_TO_SIU', label: 'Refer to SIU', icon: 'policy', style: 'btn-secondary' },
  { action: 'REQUEST_DENIAL', label: 'Propose denial', icon: 'block', style: 'btn-secondary !text-destructive' },
  { action: 'REASSIGN', label: 'Reassign', icon: 'swap_horiz', style: 'btn-secondary' },
  { action: 'REOPEN', label: 'Reopen', icon: 'restart_alt', style: 'btn-secondary' },
  { action: 'CLOSE', label: 'Close claim', icon: 'task_alt', style: 'btn-primary' },
];

@Component({
  selector: 'app-claim-workspace',
  imports: [DatePipe, StatusBadgeComponent, SkeletonComponent, LabelPipe, MoneyPipe, ClaimDocumentsComponent,
    ClaimFinancialsComponent, ClaimActivitiesComponent, ClaimTimelineComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (claim(); as c) {
      <div class="flex flex-wrap items-start justify-between gap-4 mb-4">
        <div>
          <h1 class="text-[22px] font-bold text-primary mb-1 flex items-center gap-3 flex-wrap">
            {{ c.claimNumber }}
            <app-status-badge [status]="c.status" />
            @if (c.closeOutcome) { <app-status-badge [status]="c.closeOutcome" /> }
            @if (c.segment) { <app-status-badge [status]="c.segment" /> }
          </h1>
          <p class="text-[13.5px] text-gray-500">
            {{ c.lossType | label }} on {{ c.lossDate | date: 'mediumDate' }} · policy {{ c.policyNumber }} ·
            {{ c.assignedAdjuster ? 'handled by ' + c.assignedAdjuster.displayName : 'unassigned' }}
          </p>
        </div>
        <div class="flex flex-wrap gap-2">
          @for (cmd of commands(); track cmd.action) {
            <button [class]="cmd.style" [disabled]="busy()" (click)="run(cmd.action)">
              <span class="material-icons text-[16px]">{{ cmd.icon }}</span>{{ cmd.label }}
            </button>
          }
        </div>
      </div>

      @if (c.status === 'SIU_REVIEW') {
        <div class="flex items-center gap-2 p-3 mb-4 rounded-lg bg-[#EDE9FE] text-[#5B21B6] text-[13px]">
          <span class="material-icons text-[18px]">policy</span>
          Under SIU review: payments are on hold. The claimant sees "in review".
        </div>
      }

      <div class="flex gap-1 border-b border-gray-200 mb-4">
        @for (t of tabs; track t.id) {
          <button (click)="tab.set(t.id)"
                  class="px-4 py-2.5 text-[13px] font-medium -mb-px border-b-2 transition-colors"
                  [class]="tab() === t.id ? 'border-primary text-primary' : 'border-transparent text-gray-500 hover:text-gray-800'">
            {{ t.label }}
          </button>
        }
      </div>

      @switch (tab()) {
        @case ('overview') {
          <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <div class="card p-5 lg:col-span-2">
              <h3 class="text-sm font-bold text-primary mb-3">Loss</h3>
              <dl class="grid grid-cols-2 gap-x-6 gap-y-3 text-[13px]">
                <div><dt class="text-gray-500">Where</dt><dd class="text-gray-800">{{ c.lossLocation }}</dd></div>
                <div><dt class="text-gray-500">Injuries</dt><dd class="text-gray-800">{{ c.injuriesReported ? 'Yes' : 'No' }}</dd></div>
                <div class="col-span-2"><dt class="text-gray-500">Description</dt><dd class="text-gray-800 whitespace-pre-line">{{ c.description }}</dd></div>
                <div><dt class="text-gray-500">Reporter's estimate</dt><dd class="text-gray-800">{{ c.estimatedLoss | money }}</dd></div>
                <div><dt class="text-gray-500">Paid to date</dt><dd class="text-gray-800 font-semibold">{{ c.amountPaid | money }}</dd></div>
                <div><dt class="text-gray-500">Contact</dt><dd class="text-gray-800">{{ c.contactName }} {{ c.contactPhone ? '· ' + c.contactPhone : '' }}</dd></div>
                <div><dt class="text-gray-500">Policy check</dt><dd><app-status-badge [status]="c.policyVerification" /></dd></div>
                <div><dt class="text-gray-500">Reported</dt><dd class="text-gray-800">{{ c.createdAt | date: 'medium' }}</dd></div>
                <div><dt class="text-gray-500">Last change</dt><dd class="text-gray-800">{{ c.updatedAt | date: 'medium' }}</dd></div>
              </dl>
              @if (c.openInfoRequest; as request) {
                <div class="mt-4 p-3 rounded-lg bg-amber-50 border border-amber-200 text-[13px]">
                  <p class="font-semibold text-warning">Waiting for the claimant since {{ request.requestedAt | date: 'medium' }}</p>
                  <p class="text-gray-700 mt-1">{{ request.message }}</p>
                </div>
              }
            </div>
            <div class="space-y-4">
              <div class="card p-5">
                <h3 class="text-sm font-bold text-primary mb-2">Fraud score</h3>
                <p class="text-[34px] font-bold" [style.color]="scoreColor(c.fraudScore)">{{ c.fraudScore ?? '—' }}</p>
                <p class="text-[12px] text-gray-500">0-100 from rules and AI signals; 70 or more goes to SIU. Reasons are in the timeline.</p>
                @if (c.flags.length) {
                  <div class="mt-3 flex flex-wrap gap-1">
                    @for (f of c.flags; track f) {
                      <span class="px-1.5 py-0.5 rounded text-[10px] font-semibold"
                            [class]="f === 'HIGH_FRAUD_SCORE' ? 'bg-[#FEE2E2] text-[#991B1B]' : 'bg-[#FEF3C7] text-[#92400E]'">{{ f | label }}</span>
                    }
                  </div>
                }
              </div>
              @if (siuCases().length) {
                <div class="card p-5">
                  <h3 class="text-sm font-bold text-primary mb-2">SIU</h3>
                  @for (s of siuCases(); track s.id) {
                    <div class="text-[13px] py-2 border-b border-gray-100 last:border-0">
                      <div class="flex items-center justify-between">
                        <span class="text-gray-700">{{ s.source === 'RULE' ? 'Referred by the triage rule' : 'Referred by ' + s.referredBy?.displayName }}</span>
                        <app-status-badge [status]="s.status" />
                      </div>
                      <p class="text-gray-500 mt-1">{{ s.reason }}</p>
                      @if (s.findings) { <p class="text-gray-800 mt-1"><b>Findings:</b> {{ s.findings }}</p> }
                    </div>
                  }
                </div>
              }
            </div>
          </div>
        }
        @case ('documents') { <app-claim-documents [claim]="c" /> }
        @case ('financials') { <app-claim-financials [claim]="c" (changed)="load()" /> }
        @case ('activities') { <app-claim-activities [claimId]="c.id" /> }
        @case ('timeline') { <app-claim-timeline [claim]="c" /> }
      }
    } @else {
      <div class="card"><app-skeleton [rows]="6" /></div>
    }
  `,
})
export class ClaimWorkspaceComponent implements OnInit {
  private api = inject(StaffClaimsApi);
  private financials = inject(FinancialsApi);
  private work = inject(WorkApi);
  private dialog = inject(MatDialog);
  private toast = inject(ToastService);
  auth = inject(AuthService);

  id = input.required<string>();

  private current = signal<Versioned<StaffClaim> | null>(null);
  claim = computed(() => this.current()?.value ?? null);
  siuCases = signal<SiuCase[]>([]);
  busy = signal(false);
  tab = signal<Tab>('overview');
  tabs: { id: Tab; label: string }[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'documents', label: 'Documents & AI' },
    { id: 'financials', label: 'Financials' },
    { id: 'activities', label: 'Activities' },
    { id: 'timeline', label: 'Timeline & notes' },
  ];

  commands = computed(() => {
    const allowed = this.claim()?.allowedActions ?? [];
    return COMMANDS.filter((c) => allowed.includes(c.action));
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.claim(+this.id()).subscribe({ next: (c) => this.current.set(c), error: (e) => this.toast.error(e) });
    this.work.claimSiuCases(+this.id()).subscribe({ next: (s) => this.siuCases.set(s), error: () => this.siuCases.set([]) });
  }

  scoreColor(score: number | null | undefined): string {
    return score === null || score === undefined ? '#9ca3af' : score >= 70 ? '#DC2626' : score >= 40 ? '#D97706' : '#059669';
  }

  run(action: ClaimAction): void {
    const c = this.current()!;
    const id = c.value.id;
    switch (action) {
      case 'REQUEST_INFO':
        return this.withReason({ title: 'Ask the claimant', label: 'Your question', confirmText: 'Send',
          message: 'The claim waits for the answer. The claimant is reminded after 3 days; after 14 it comes back to you.' },
          (text) => this.api.requestInfo(id, c.etag, text), 'Question sent');
      case 'CANCEL_INFO_REQUEST':
        return this.withReason({ title: 'Withdraw the question', confirmText: 'Withdraw' },
          (text) => this.api.cancelInfoRequest(id, c.etag, text), 'Question withdrawn');
      case 'REFER_TO_SIU':
        return this.withReason({ title: 'Refer to SIU', confirmText: 'Refer', danger: true,
          message: 'Payments are held until SIU records an outcome. The claimant is not told.' },
          (text) => this.api.referToSiu(id, c.etag, text), 'Referred to SIU');
      case 'CLOSE':
        return this.withReason({ title: 'Close the claim', confirmText: 'Close',
          message: 'All exposures must be closed and nothing may be waiting for payment or approval.' },
          (text) => this.api.close(id, c.etag, text), 'Claim closed');
      case 'REOPEN':
        return this.withReason({ title: 'Reopen the claim', confirmText: 'Reopen' },
          (text) => this.api.reopen(id, c.etag, text), 'Claim reopened');
      case 'REQUEST_DENIAL':
        askReason(this.dialog, { title: 'Propose a denial', confirmText: 'Send to a supervisor', danger: true,
          label: 'Reason and policy clause', message: 'A supervisor decides; the claim stays open until then.' })
          .subscribe((text) => text && this.financials.requestDenial(id, text).subscribe({
            next: () => this.toast.success('Denial proposed; it is in the supervisors\' approval queue.'),
            error: (e) => this.toast.error(e),
          }));
        return;
      case 'REASSIGN':
        this.dialog.open<ReassignDialogComponent, { currentId: number | null }, ReassignResult>(ReassignDialogComponent, {
          data: { currentId: c.value.assignedAdjuster?.id ?? null }, width: '480px',
        }).afterClosed().subscribe((result) => result && this.apply(
          this.api.reassign(id, c.etag, result.adjusterId, result.reason), 'Claim reassigned'));
        return;
      default:
        return;
    }
  }

  private withReason(data: ReasonDialogData, command: (text: string) => Observable<Versioned<StaffClaim>>, done: string): void {
    askReason(this.dialog, data).subscribe((text) => text && this.apply(command(text), done));
  }

  private apply(command: Observable<Versioned<StaffClaim>>, done: string): void {
    this.busy.set(true);
    command.subscribe({
      next: (updated) => {
        this.current.set(updated);
        this.busy.set(false);
        this.toast.success(done);
        this.work.claimSiuCases(updated.value.id).subscribe((s) => this.siuCases.set(s));
      },
      error: (e) => {
        this.busy.set(false);
        this.toast.error(e);
        if (apiError(e).status === 412) {
          this.load();
        }
      },
    });
  }
}
