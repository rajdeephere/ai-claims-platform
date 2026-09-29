import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { WorkApi } from '../../core/api/work.api';
import { CaseFile } from '../../core/api/api.types';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/toast.service';
import { LabelPipe } from '../../shared/labels.pipe';
import { MoneyPipe } from '../../shared/money.pipe';
import { SkeletonComponent } from '../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

/**
 * The investigator's case file (ADR-0026). The outcome ends the review: CLEARED resumes handling;
 * CONFIRMED proposes a denial that a supervisor decides. Documents, AI results and the timeline are on the claim.
 */
@Component({
  selector: 'app-siu-case',
  imports: [DatePipe, FormsModule, RouterLink, StatusBadgeComponent, SkeletonComponent, LabelPipe, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (file(); as f) {
      <div class="flex flex-wrap items-center justify-between gap-4 mb-5">
        <div>
          <h1 class="text-[22px] font-bold text-primary mb-0.5 flex items-center gap-3">
            {{ f.claim.claimNumber }} <app-status-badge [status]="f.siuCase.status" />
          </h1>
          <p class="text-[13.5px] text-gray-500">
            {{ f.siuCase.source === 'RULE' ? 'Referred by the triage rule' : 'Referred by ' + f.siuCase.referredBy?.displayName }}
            on {{ f.siuCase.referredAt | date: 'medium' }}
          </p>
        </div>
        <a [routerLink]="['/claims', f.claim.id]" class="btn-secondary"><span class="material-icons text-[16px]">open_in_new</span>Documents, AI, timeline</a>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div class="lg:col-span-2 space-y-4">
          <div class="card p-5">
            <h3 class="text-sm font-bold text-primary mb-3">Referral</h3>
            <p class="text-[13.5px] text-gray-800">{{ f.siuCase.reason }}</p>
            <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mt-4 text-[13px]">
              <div><p class="text-gray-500">Score at referral</p><p class="text-xl font-bold text-destructive">{{ f.siuCase.fraudScoreAtReferral ?? '—' }}</p></div>
              <div><p class="text-gray-500">Score now</p><p class="text-xl font-bold">{{ f.claim.fraudScore ?? '—' }}</p></div>
              <div><p class="text-gray-500">Claim status</p><app-status-badge [status]="f.claim.status" /></div>
              <div><p class="text-gray-500">Estimate</p><p class="font-semibold">{{ f.claim.estimatedLoss | money }}</p></div>
            </div>
          </div>
          <div class="card p-5">
            <h3 class="text-sm font-bold text-primary mb-3">The loss</h3>
            <p class="text-[13px] text-gray-500 mb-1">{{ f.claim.lossType | label }} on {{ f.claim.lossDate | date: 'mediumDate' }} · policy {{ f.claim.policyNumber }}</p>
            <p class="text-[13.5px] text-gray-800 whitespace-pre-line">{{ f.claim.description }}</p>
          </div>
          <div class="card overflow-hidden">
            <div class="px-5 py-3 border-b border-gray-100"><h3 class="text-sm font-bold text-primary">Other claims on this policy</h3></div>
            <table class="w-full">
              <tbody>
                @for (o of f.otherClaimsOnPolicy; track o.id) {
                  <tr>
                    <td class="td font-semibold text-primary">{{ o.claimNumber }}</td>
                    <td class="td text-gray-700">{{ o.lossType | label }}</td>
                    <td class="td text-gray-500">{{ o.lossDate | date: 'mediumDate' }}</td>
                    <td class="td"><app-status-badge [status]="o.status" /></td>
                    <td class="td font-bold">{{ o.fraudScore ?? '—' }}</td>
                  </tr>
                } @empty {
                  <tr><td class="td text-gray-400">None: this is the policy's only claim.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>

        <div class="card p-5 self-start">
          <h3 class="text-sm font-bold text-primary mb-3">Outcome</h3>
          @if (f.siuCase.status === 'OPEN') {
            @if (auth.hasRole('SIU')) {
              <div class="flex gap-2 mb-3">
                <button class="flex-1 btn-secondary" [class.!border-success]="outcome === 'CLEARED'" [class.!text-success]="outcome === 'CLEARED'"
                        (click)="outcome = 'CLEARED'">Cleared</button>
                <button class="flex-1 btn-secondary" [class.!border-destructive]="outcome === 'CONFIRMED'" [class.!text-destructive]="outcome === 'CONFIRMED'"
                        (click)="outcome = 'CONFIRMED'">Fraud confirmed</button>
              </div>
              <label class="label">Findings</label>
              <textarea rows="6" class="input" [(ngModel)]="findings" maxlength="4000"
                        placeholder="What you checked, what you found"></textarea>
              <p class="text-[11px] text-gray-500 mt-2">
                {{ outcome === 'CONFIRMED' ? 'The claim goes back to handling with a denial proposed to a supervisor.'
                  : 'The claim goes back to normal handling; held payments are released.' }}
              </p>
              <button class="btn-primary w-full mt-3" [disabled]="!outcome || !findings.trim() || busy()" (click)="record()">Record outcome</button>
            } @else {
              <p class="text-[13px] text-gray-500">Waiting for an SIU investigator.</p>
            }
          } @else {
            <p class="text-[13px] text-gray-800"><b>{{ f.siuCase.status | label }}</b> by {{ f.siuCase.investigator?.displayName }}
              on {{ f.siuCase.decidedAt | date: 'medium' }}</p>
            <p class="text-[13px] text-gray-700 mt-2 whitespace-pre-line">{{ f.siuCase.findings }}</p>
          }
        </div>
      </div>
    } @else {
      <div class="card"><app-skeleton [rows]="6" /></div>
    }
  `,
})
export class SiuCaseComponent implements OnInit {
  auth = inject(AuthService);
  private work = inject(WorkApi);
  private toast = inject(ToastService);

  id = input.required<string>();
  file = signal<CaseFile | null>(null);
  busy = signal(false);
  outcome: 'CLEARED' | 'CONFIRMED' | null = null;
  findings = '';

  ngOnInit(): void {
    this.load();
  }

  record(): void {
    this.busy.set(true);
    this.work.recordSiuOutcome(+this.id(), this.outcome!, this.findings.trim()).subscribe({
      next: () => {
        this.busy.set(false);
        this.toast.success(this.outcome === 'CONFIRMED' ? 'Recorded; a denial was proposed to the supervisors.' : 'Recorded; the claim is back in handling.');
        this.load();
      },
      error: (e) => {
        this.busy.set(false);
        this.toast.error(e);
      },
    });
  }

  private load(): void {
    this.work.siuCase(+this.id()).subscribe({ next: (f) => this.file.set(f), error: (e) => this.toast.error(e) });
  }
}
