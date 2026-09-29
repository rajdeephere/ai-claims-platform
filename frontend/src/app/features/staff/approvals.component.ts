import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { FinancialsApi } from '../../core/api/work.api';
import { Approval } from '../../core/api/api.types';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { LabelPipe } from '../../shared/labels.pipe';
import { MoneyPipe } from '../../shared/money.pipe';
import { PageHeaderComponent } from '../../shared/page-header.component';
import { askReason } from '../../shared/reason-dialog.component';
import { SkeletonComponent } from '../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

/**
 * Maker-checker (ADR-0024): payments and reserves above the requester's authority, and every denial.
 * The server refuses a decision on your own request and above your own limit; the UI says why up front.
 */
@Component({
  selector: 'app-approvals',
  imports: [DatePipe, RouterLink, PageHeaderComponent, StatusBadgeComponent, EmptyStateComponent, SkeletonComponent, LabelPipe, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="Approvals" subtitle="Oldest first. You can't decide your own requests.">
      <span class="text-[13px] text-gray-500">Your authority: <b class="text-gray-800">{{ auth.user()?.authorityLimit | money }}</b></span>
    </app-page-header>

    <div class="card overflow-hidden">
      @if (loading()) {
        <app-skeleton />
      } @else if (rows().length === 0) {
        <app-empty-state icon="fact_check" message="Nothing is waiting for approval." />
      } @else {
        <table class="w-full">
          <thead><tr>
            <th class="th">Request</th><th class="th">Claim</th><th class="th text-right">Amount</th><th class="th">Reason</th><th class="th">Requested</th><th class="th"></th>
          </tr></thead>
          <tbody>
            @for (a of rows(); track a.id) {
              <tr>
                <td class="td"><app-status-badge [status]="a.kind === 'DENIAL' ? 'DENIED' : 'PENDING_APPROVAL'" />
                  <span class="ml-2 text-gray-800 font-medium">{{ a.kind | label }}</span></td>
                <td class="td"><a [routerLink]="['/claims', a.claimId]" class="text-primary font-semibold hover:underline">claim #{{ a.claimId }}</a></td>
                <td class="td text-right font-semibold">{{ a.amount | money }}</td>
                <td class="td text-gray-700 max-w-[320px]">{{ a.reason }}</td>
                <td class="td text-gray-400">{{ a.requestedAt | date: 'short' }}
                  @if (mine(a)) { <br /><span class="text-[11px] text-warning font-semibold">your request</span> }</td>
                <td class="td text-right whitespace-nowrap">
                  @if (mine(a)) {
                    <span class="text-[12px] text-gray-400">another supervisor decides</span>
                  } @else {
                    @if (aboveMyLimit(a)) {
                      <span class="block text-[11px] text-warning mb-1">above your authority</span>
                    }
                    <button class="btn-secondary !px-3 !py-1.5 mr-2" (click)="reject(a)">Reject</button>
                    <button class="btn-accent !px-3 !py-1.5" [disabled]="aboveMyLimit(a)" (click)="approve(a)">Approve</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
})
export class ApprovalsComponent implements OnInit {
  auth = inject(AuthService);
  private api = inject(FinancialsApi);
  private dialog = inject(MatDialog);
  private toast = inject(ToastService);

  loading = signal(true);
  rows = signal<Approval[]>([]);

  ngOnInit(): void {
    this.load();
  }

  mine(a: Approval): boolean {
    return a.requestedBy === this.auth.user()?.id;
  }

  aboveMyLimit(a: Approval): boolean {
    return a.amount !== null && a.amount !== undefined && a.amount > (this.auth.user()?.authorityLimit ?? 0);
  }

  approve(a: Approval): void {
    askReason(this.dialog, { title: `Approve ${a.kind.toLowerCase().replace('_', ' ')}`, message: a.reason,
      label: 'Comment (optional)', confirmText: 'Approve', required: false, danger: a.kind === 'DENIAL' })
      .subscribe((comment) => comment !== undefined && this.api.approve(a.id, comment).subscribe({
        next: () => this.done(a.kind === 'DENIAL' ? 'Denial approved: the claim is closed.' : 'Approved'),
        error: (e) => this.toast.error(e),
      }));
  }

  reject(a: Approval): void {
    askReason(this.dialog, { title: `Reject ${a.kind.toLowerCase().replace('_', ' ')}`, message: a.reason,
      confirmText: 'Reject', danger: true })
      .subscribe((reason) => reason && this.api.reject(a.id, reason).subscribe({
        next: () => this.done('Rejected'),
        error: (e) => this.toast.error(e),
      }));
  }

  private done(message: string): void {
    this.toast.success(message);
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.api.approvals().subscribe({
      next: (p) => {
        this.rows.set(p.content);
        this.loading.set(false);
      },
      error: (e) => {
        this.toast.error(e);
        this.loading.set(false);
      },
    });
  }
}
