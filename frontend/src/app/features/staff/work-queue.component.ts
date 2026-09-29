import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { QueueFilter, StaffClaimsApi } from '../../core/api/claims.api';
import { ClaimStatus, StaffClaimSummary } from '../../core/api/api.types';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { LabelPipe } from '../../shared/labels.pipe';
import { MoneyPipe } from '../../shared/money.pipe';
import { PageHeaderComponent } from '../../shared/page-header.component';
import { SkeletonComponent } from '../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

/** Adjusters see their own claims (the server enforces it); supervisors see all and can filter. */
@Component({
  selector: 'app-work-queue',
  imports: [DatePipe, FormsModule, MatPaginatorModule, PageHeaderComponent, StatusBadgeComponent, EmptyStateComponent,
    SkeletonComponent, LabelPipe, MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="Work queue"
      [subtitle]="auth.hasRole('SUPERVISOR') ? 'Every claim, newest first' : 'Claims assigned to you, newest first'" />

    <div class="card p-4 mb-4">
      <div class="flex flex-wrap gap-3 items-center">
        <select class="input !w-auto" [(ngModel)]="filter.status" (ngModelChange)="reload()">
          <option value="">All statuses</option>
          @for (s of statuses; track s) {
            <option [value]="s">{{ s | label }}</option>
          }
        </select>
        <select class="input !w-auto" [(ngModel)]="filter.segment" (ngModelChange)="reload()">
          <option value="">All segments</option>
          <option value="FAST_TRACK">Fast track</option>
          <option value="STANDARD">Standard</option>
          <option value="COMPLEX">Complex</option>
        </select>
        <button class="btn-secondary" (click)="clear()">Clear</button>
        <span class="ml-auto text-[13px] text-gray-500">{{ total() }} total</span>
      </div>
    </div>

    <div class="card overflow-hidden">
      @if (loading()) {
        <app-skeleton [rows]="6" />
      } @else if (rows().length === 0) {
        <app-empty-state icon="inventory_2" message="No claims match." />
      } @else {
        <table class="w-full">
          <thead>
            <tr>
              <th class="th">Claim</th>
              <th class="th">Loss</th>
              <th class="th">Status</th>
              <th class="th">Segment</th>
              <th class="th">Estimate</th>
              <th class="th">Adjuster</th>
              <th class="th">Flags</th>
              <th class="th">Reported</th>
            </tr>
          </thead>
          <tbody>
            @for (c of rows(); track c.id) {
              <tr class="cursor-pointer hover:bg-[#f8fafc] transition-colors" (click)="open(c)">
                <td class="td font-semibold text-primary">{{ c.claimNumber }}</td>
                <td class="td text-gray-700">{{ c.lossType | label }}<br /><span class="text-[11px] text-gray-400">{{ c.lossDate | date: 'mediumDate' }}</span></td>
                <td class="td"><app-status-badge [status]="c.status" /></td>
                <td class="td">@if (c.segment) { <app-status-badge [status]="c.segment" /> } @else { <span class="text-gray-400">—</span> }</td>
                <td class="td text-gray-700">{{ c.estimatedLoss | money }}</td>
                <td class="td text-gray-700">{{ c.assignedAdjuster ? c.assignedAdjuster.displayName : '—' }}</td>
                <td class="td">
                  @for (f of c.flags; track f) {
                    <span class="inline-block mr-1 mb-1 px-1.5 py-0.5 rounded text-[10px] font-semibold"
                          [class]="f === 'HIGH_FRAUD_SCORE' ? 'bg-[#FEE2E2] text-[#991B1B]' : 'bg-[#FEF3C7] text-[#92400E]'">{{ f | label }}</span>
                  }
                </td>
                <td class="td text-gray-400">{{ c.createdAt | date: 'short' }}</td>
              </tr>
            }
          </tbody>
        </table>
        <div class="px-4 py-2 border-t border-gray-200 flex justify-end">
          <mat-paginator [length]="total()" [pageSize]="filter.size" [pageIndex]="filter.page"
                         [pageSizeOptions]="[20, 50, 100]" (page)="page($event)" showFirstLastButtons />
        </div>
      }
    </div>
  `,
})
export class WorkQueueComponent implements OnInit {
  auth = inject(AuthService);
  private api = inject(StaffClaimsApi);
  private router = inject(Router);
  private toast = inject(ToastService);

  /** ?status= from the dashboard's cards */
  status = input<string>();

  statuses: ClaimStatus[] = ['SUBMITTED', 'ASSESSING', 'OPEN', 'AWAITING_INFO', 'SIU_REVIEW', 'CLOSED'];
  filter: Required<Pick<QueueFilter, 'page' | 'size'>> & QueueFilter = { status: '', segment: '', page: 0, size: 20 };
  loading = signal(true);
  rows = signal<StaffClaimSummary[]>([]);
  total = signal(0);

  ngOnInit(): void {
    this.filter.status = (this.status() as ClaimStatus) ?? '';
    this.load();
  }

  reload(): void {
    this.filter.page = 0;
    this.load();
  }

  clear(): void {
    this.filter = { status: '', segment: '', page: 0, size: this.filter.size };
    this.load();
  }

  page(e: PageEvent): void {
    this.filter.page = e.pageIndex;
    this.filter.size = e.pageSize;
    this.load();
  }

  open(c: StaffClaimSummary): void {
    this.router.navigate(['/claims', c.id]);
  }

  private load(): void {
    this.loading.set(true);
    this.api.queue(this.filter).subscribe({
      next: (p) => {
        this.rows.set(p.content);
        this.total.set(p.totalElements);
        this.loading.set(false);
      },
      error: (e) => {
        this.toast.error(e);
        this.loading.set(false);
      },
    });
  }
}
