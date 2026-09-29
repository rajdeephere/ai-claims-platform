import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { WorkApi } from '../../core/api/work.api';
import { SiuCase } from '../../core/api/api.types';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { PageHeaderComponent } from '../../shared/page-header.component';
import { SkeletonComponent } from '../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

@Component({
  selector: 'app-siu-queue',
  imports: [DatePipe, PageHeaderComponent, StatusBadgeComponent, EmptyStateComponent, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="SIU cases" subtitle="Referrals by the triage rule or by staff, oldest first" />

    <div class="flex gap-2 mb-4">
      @for (s of statuses; track s) {
        <button class="px-3.5 py-1.5 rounded-full text-[13px] font-medium border transition-colors"
                [class]="status() === s ? 'bg-primary text-white border-primary' : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'"
                (click)="show(s)">{{ s === 'OPEN' ? 'Open' : s === 'CLEARED' ? 'Cleared' : 'Confirmed' }}</button>
      }
    </div>

    <div class="card overflow-hidden">
      @if (loading()) {
        <app-skeleton />
      } @else if (rows().length === 0) {
        <app-empty-state icon="policy" message="No cases." />
      } @else {
        <table class="w-full">
          <thead><tr>
            <th class="th">Claim</th><th class="th">Referred by</th><th class="th">Reason</th><th class="th">Score</th><th class="th">Status</th><th class="th">Referred</th>
          </tr></thead>
          <tbody>
            @for (c of rows(); track c.id) {
              <tr class="cursor-pointer hover:bg-[#f8fafc]" (click)="open(c)">
                <td class="td font-semibold text-primary">{{ c.claimNumber }}</td>
                <td class="td text-gray-700">{{ c.source === 'RULE' ? 'Triage rule' : c.referredBy?.displayName }}</td>
                <td class="td text-gray-700 max-w-[360px] truncate">{{ c.reason }}</td>
                <td class="td font-bold" [class]="(c.fraudScoreAtReferral ?? 0) >= 70 ? 'text-destructive' : 'text-gray-700'">{{ c.fraudScoreAtReferral ?? '—' }}</td>
                <td class="td"><app-status-badge [status]="c.status" /></td>
                <td class="td text-gray-400">{{ c.referredAt | date: 'short' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
})
export class SiuQueueComponent implements OnInit {
  private work = inject(WorkApi);
  private router = inject(Router);
  private toast = inject(ToastService);

  statuses = ['OPEN', 'CLEARED', 'CONFIRMED'];
  status = signal('OPEN');
  loading = signal(true);
  rows = signal<SiuCase[]>([]);

  ngOnInit(): void {
    this.load();
  }

  show(status: string): void {
    this.status.set(status);
    this.load();
  }

  open(c: SiuCase): void {
    this.router.navigate(['/siu', c.id]);
  }

  private load(): void {
    this.loading.set(true);
    this.work.siuQueue(this.status()).subscribe({
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
