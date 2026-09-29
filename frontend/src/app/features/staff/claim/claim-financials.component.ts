import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { FinancialsApi } from '../../../core/api/work.api';
import { Exposure, Payment, Recovery, ReserveOutcome, StaffClaim } from '../../../core/api/api.types';
import { AuthService } from '../../../core/auth/auth.service';
import { apiError } from '../../../core/http/api-errors';
import { ToastService } from '../../../core/toast.service';
import { LabelPipe } from '../../../shared/labels.pipe';
import { MoneyPipe } from '../../../shared/money.pipe';
import { askReason } from '../../../shared/reason-dialog.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';

type Panel = 'exposure' | 'reserve' | 'payment' | 'recovery' | null;

/**
 * Exposures, reserves, payments and recoveries (ADR-0024). Within the user's authority a change applies at
 * once; above it the server creates an approval request, and this screen says so.
 */
@Component({
  selector: 'app-claim-financials',
  imports: [DatePipe, FormsModule, StatusBadgeComponent, LabelPipe, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-wrap items-center justify-between gap-3 mb-3">
      <p class="text-[13px] text-gray-500">
        Your authority: <b class="text-gray-800">{{ auth.user()?.authorityLimit | money }}</b>.
        Above it, a supervisor approves (never the requester).
      </p>
      <div class="flex gap-2">
        @if (can('MANAGE_EXPOSURES')) {
          <button class="btn-secondary" (click)="toggle('exposure')"><span class="material-icons text-[16px]">add</span>Exposure</button>
        }
        @if (can('REQUEST_PAYMENT') && openExposures().length) {
          <button class="btn-primary" (click)="toggle('payment')"><span class="material-icons text-[16px]">payments</span>Request payment</button>
        }
        @if (can('RECORD_RECOVERY')) {
          <button class="btn-secondary" (click)="toggle('recovery')"><span class="material-icons text-[16px]">savings</span>Recovery</button>
        }
      </div>
    </div>

    @switch (panel()) {
      @case ('exposure') {
        <div class="card p-4 mb-4 grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
          <div><label class="label">Type</label>
            <select class="input" [(ngModel)]="exposureForm.type">
              <option value="VEHICLE_DAMAGE">Vehicle damage</option>
              <option value="PROPERTY_DAMAGE">Property damage</option>
              <option value="BODILY_INJURY">Bodily injury</option>
            </select></div>
          <div><label class="label">Claimant</label><input class="input" [(ngModel)]="exposureForm.claimantName" /></div>
          <div><label class="label">Initial reserve (₹)</label><input class="input" type="number" min="0" step="0.01" [(ngModel)]="exposureForm.reserve" /></div>
          <div><label class="label">Reason</label><input class="input" [(ngModel)]="exposureForm.reason" placeholder="first estimate" /></div>
          <div class="md:col-span-4 flex justify-end gap-2">
            <button class="btn-secondary" (click)="panel.set(null)">Cancel</button>
            <button class="btn-accent" [disabled]="!exposureForm.claimantName.trim()" (click)="createExposure()">Create exposure</button>
          </div>
        </div>
      }
      @case ('reserve') {
        <div class="card p-4 mb-4 grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
          <div class="md:col-span-4 text-[13px] text-gray-600">
            {{ reserveForm.exposure?.type | label }}: reserve {{ reserveForm.exposure?.reserve | money }}, paid {{ reserveForm.exposure?.paid | money }},
            committed {{ reserveForm.exposure?.committed | money }}. Lowering applies at once; raising above your authority needs approval.
          </div>
          <div><label class="label">New reserve (₹)</label><input class="input" type="number" min="0" step="0.01" [(ngModel)]="reserveForm.amount" /></div>
          <div class="md:col-span-2"><label class="label">Reason</label><input class="input" [(ngModel)]="reserveForm.reason" placeholder="second garage quote" /></div>
          <div class="flex justify-end gap-2">
            <button class="btn-secondary" (click)="panel.set(null)">Cancel</button>
            <button class="btn-accent" [disabled]="reserveForm.amount === null || !reserveForm.reason.trim()" (click)="saveReserve()">Set reserve</button>
          </div>
        </div>
      }
      @case ('payment') {
        <div class="card p-4 mb-4 grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
          <div><label class="label">From exposure</label>
            <select class="input" [(ngModel)]="paymentForm.exposureId" (ngModelChange)="newPaymentKey()">
              @for (e of openExposures(); track e.id) {
                <option [ngValue]="e.id">{{ e.type | label }} · {{ e.available | money }} available</option>
              }
            </select></div>
          <div><label class="label">Amount (₹)</label><input class="input" type="number" min="0.01" step="0.01" [(ngModel)]="paymentForm.amount" (ngModelChange)="newPaymentKey()" /></div>
          <div><label class="label">Payee</label><input class="input" [(ngModel)]="paymentForm.payeeName" (ngModelChange)="newPaymentKey()" placeholder="City Motors Pvt Ltd" /></div>
          <div><label class="label">Reason</label><input class="input" [(ngModel)]="paymentForm.reason" placeholder="repair invoice" /></div>
          <div class="md:col-span-4 flex justify-end gap-2">
            <button class="btn-secondary" (click)="panel.set(null)">Cancel</button>
            <button class="btn-primary" [disabled]="busy() || !paymentForm.exposureId || !paymentForm.amount || !paymentForm.payeeName.trim()"
                    (click)="requestPayment()">Request payment</button>
          </div>
        </div>
      }
      @case ('recovery') {
        <div class="card p-4 mb-4 grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
          <div><label class="label">Amount (₹)</label><input class="input" type="number" min="0.01" step="0.01" [(ngModel)]="recoveryForm.amount" /></div>
          <div><label class="label">Source</label>
            <select class="input" [(ngModel)]="recoveryForm.source">
              <option value="THIRD_PARTY_INSURER">Third-party insurer</option>
              <option value="THIRD_PARTY">Third party</option>
              <option value="SALVAGE">Salvage</option>
              <option value="OTHER">Other</option>
            </select></div>
          <div><label class="label">Reference</label><input class="input" [(ngModel)]="recoveryForm.reference" placeholder="SUB-778" /></div>
          <div><label class="label">Received on</label><input class="input" type="date" [max]="today" [(ngModel)]="recoveryForm.receivedOn" /></div>
          <div class="md:col-span-4 flex justify-end gap-2">
            <button class="btn-secondary" (click)="panel.set(null)">Cancel</button>
            <button class="btn-accent" [disabled]="!recoveryForm.amount" (click)="recordRecovery()">Record recovery</button>
          </div>
        </div>
      }
    }

    <div class="card overflow-hidden mb-4">
      <div class="px-5 py-3 border-b border-gray-100"><h3 class="text-sm font-bold text-primary">Exposures</h3></div>
      <table class="w-full">
        <thead><tr>
          <th class="th">Exposure</th><th class="th">Status</th><th class="th text-right">Reserve</th><th class="th text-right">Paid</th>
          <th class="th text-right">Committed</th><th class="th text-right">Available</th><th class="th"></th>
        </tr></thead>
        <tbody>
          @for (e of exposures(); track e.id) {
            <tr>
              <td class="td"><span class="font-semibold text-primary">{{ e.type | label }}</span>
                <br /><span class="text-[11px] text-gray-400">{{ e.claimantName }} · {{ e.coverageType | label }}</span></td>
              <td class="td"><app-status-badge [status]="e.status" /></td>
              <td class="td text-right">{{ e.reserve | money }}</td>
              <td class="td text-right">{{ e.paid | money }}</td>
              <td class="td text-right text-warning">{{ e.committed | money }}</td>
              <td class="td text-right font-semibold">{{ e.available | money }}</td>
              <td class="td text-right whitespace-nowrap">
                @if (e.status === 'OPEN' && can('MANAGE_EXPOSURES')) {
                  <button class="text-[12px] text-primary font-semibold hover:underline mr-3" (click)="changeReserve(e)">Reserve</button>
                  <button class="text-[12px] text-gray-500 font-semibold hover:underline" (click)="closeExposure(e)">Close</button>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="td text-center text-gray-400">No exposures yet. Create one to set a reserve.</td></tr>
          }
        </tbody>
      </table>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
      <div class="card overflow-hidden lg:col-span-2">
        <div class="px-5 py-3 border-b border-gray-100"><h3 class="text-sm font-bold text-primary">Payments</h3></div>
        <table class="w-full">
          <thead><tr><th class="th">Payee</th><th class="th text-right">Amount</th><th class="th">Status</th><th class="th">Reference</th><th class="th">Requested</th></tr></thead>
          <tbody>
            @for (p of payments(); track p.id) {
              <tr>
                <td class="td text-gray-800">{{ p.payeeName }}</td>
                <td class="td text-right font-semibold">{{ p.amount | money }}</td>
                <td class="td"><app-status-badge [status]="p.status" />
                  @if (p.failureReason) { <br /><span class="text-[11px] text-destructive">{{ p.failureReason }}</span> }</td>
                <td class="td text-[12px] text-gray-500">{{ p.externalReference ?? '—' }}</td>
                <td class="td text-gray-400">{{ p.createdAt | date: 'short' }}</td>
              </tr>
            } @empty {
              <tr><td colspan="5" class="td text-center text-gray-400">No payments.</td></tr>
            }
          </tbody>
        </table>
      </div>
      <div class="card overflow-hidden">
        <div class="px-5 py-3 border-b border-gray-100"><h3 class="text-sm font-bold text-primary">Recoveries</h3></div>
        @for (r of recoveries(); track r.id) {
          <div class="px-5 py-2.5 border-b border-gray-100 text-[13px] flex justify-between">
            <span class="text-gray-700">{{ r.source | label }}<br /><span class="text-[11px] text-gray-400">{{ r.reference }} · {{ r.receivedOn | date: 'mediumDate' }}</span></span>
            <span class="font-semibold text-success">{{ r.amount | money }}</span>
          </div>
        } @empty {
          <p class="px-5 py-4 text-[13px] text-gray-400">No recoveries.</p>
        }
      </div>
    </div>
  `,
})
export class ClaimFinancialsComponent implements OnInit {
  private api = inject(FinancialsApi);
  private dialog = inject(MatDialog);
  private toast = inject(ToastService);
  auth = inject(AuthService);

  claim = input.required<StaffClaim>();
  /** the claim's own numbers (amount paid, allowed actions) may have changed */
  changed = output<void>();

  exposures = signal<Exposure[]>([]);
  payments = signal<Payment[]>([]);
  recoveries = signal<Recovery[]>([]);
  openExposures = computed(() => this.exposures().filter((e) => e.status === 'OPEN'));
  panel = signal<Panel>(null);
  busy = signal(false);
  today = new Date().toISOString().slice(0, 10);

  exposureForm = { type: 'VEHICLE_DAMAGE', claimantName: '', reserve: null as number | null, reason: '' };
  paymentForm = { exposureId: null as number | null, amount: null as number | null, payeeName: '', reason: '' };
  reserveForm = { exposure: null as Exposure | null, amount: null as number | null, reason: '' };
  recoveryForm = { amount: null as number | null, source: 'THIRD_PARTY_INSURER', reference: '', receivedOn: this.today };
  /** one key per payment the user means; changing what is paid makes it a different payment */
  private paymentKey = crypto.randomUUID();

  ngOnInit(): void {
    this.exposureForm.claimantName = this.claim().contactName ?? '';
    this.load();
  }

  can(action: string): boolean {
    return this.claim().allowedActions.includes(action as never);
  }

  toggle(panel: Panel): void {
    this.panel.set(this.panel() === panel ? null : panel);
    if (panel === 'payment') {
      this.paymentForm.exposureId = this.openExposures()[0]?.id ?? null;
      this.newPaymentKey();
    }
  }

  newPaymentKey(): void {
    this.paymentKey = crypto.randomUUID();
  }

  load(): void {
    const id = this.claim().id;
    this.api.exposures(id).subscribe((e) => this.exposures.set(e));
    this.api.payments(id).subscribe((p) => this.payments.set(p));
    this.api.recoveries(id).subscribe((r) => this.recoveries.set(r));
  }

  createExposure(): void {
    const f = this.exposureForm;
    this.api.createExposure(this.claim().id, { type: f.type, claimantName: f.claimantName.trim(), reserve: f.reserve, reason: f.reason })
      .subscribe({ next: (r) => this.reserveDone(r, 'Exposure created'), error: (e) => this.failed(e) });
  }

  changeReserve(e: Exposure): void {
    this.reserveForm = { exposure: e, amount: e.reserve, reason: '' };
    this.panel.set('reserve');
  }

  saveReserve(): void {
    const f = this.reserveForm;
    this.api.changeReserve(f.exposure!, f.amount!, f.reason.trim()).subscribe({
      next: (r) => this.reserveDone(r, 'Reserve updated'),
      error: (err) => this.failed(err),
    });
  }

  closeExposure(e: Exposure): void {
    askReason(this.dialog, { title: 'Close exposure', message: 'The unused reserve is released.', confirmText: 'Close' })
      .subscribe((reason) => reason && this.api.closeExposure(e, reason).subscribe({
        next: () => this.done('Exposure closed'),
        error: (err) => this.failed(err),
      }));
  }

  requestPayment(): void {
    const f = this.paymentForm;
    this.busy.set(true);
    this.api.requestPayment(f.exposureId!, { amount: f.amount!, payeeName: f.payeeName.trim(), reason: f.reason }, this.paymentKey)
      .subscribe({
        next: (p) => {
          this.busy.set(false);
          this.newPaymentKey();
          this.done(p.status === 'PENDING_APPROVAL'
            ? 'Above your authority: the payment waits for a supervisor.'
            : 'Payment approved; it is being sent to the bank.');
        },
        error: (e) => {
          this.busy.set(false);
          this.failed(e);
        },
      });
  }

  recordRecovery(): void {
    const f = this.recoveryForm;
    this.api.recordRecovery(this.claim().id, { amount: f.amount!, source: f.source, reference: f.reference, receivedOn: f.receivedOn })
      .subscribe({ next: () => this.done('Recovery recorded'), error: (e) => this.failed(e) });
  }

  private reserveDone(r: ReserveOutcome, message: string): void {
    this.done(r.pendingApproval ? 'Above your authority: the reserve change waits for a supervisor.' : message);
  }

  private done(message: string): void {
    this.toast.success(message);
    this.panel.set(null);
    this.load();
    this.changed.emit();
  }

  private failed(e: unknown): void {
    this.toast.error(e);
    if (apiError(e).status === 412) {
      this.load();
    }
  }
}
