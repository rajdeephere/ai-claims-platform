import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { WorkApi } from '../../core/api/work.api';
import { Job } from '../../core/api/api.types';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { PageHeaderComponent } from '../../shared/page-header.component';
import { SkeletonComponent } from '../../shared/skeleton.component';

/**
 * Background jobs that used up their retries (ADR-0016). Retrying is safe by design: handlers are
 * idempotent, and a payment retry reuses the payment's idempotency key.
 */
@Component({
  selector: 'app-failed-jobs',
  imports: [DatePipe, RouterLink, PageHeaderComponent, EmptyStateComponent, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="Failed jobs" subtitle="Background work that ran out of retries. Check the cause before retrying." />
    <div class="card overflow-hidden">
      @if (loading()) {
        <app-skeleton />
      } @else if (rows().length === 0) {
        <app-empty-state icon="check_circle" message="No failed jobs." />
      } @else {
        <table class="w-full">
          <thead><tr>
            <th class="th">Job</th><th class="th">Claim</th><th class="th">Attempts</th><th class="th">Last error</th><th class="th">Updated</th><th class="th"></th>
          </tr></thead>
          <tbody>
            @for (j of rows(); track j.id) {
              <tr>
                <td class="td font-mono text-[12px] text-gray-800">{{ j.type }}<br /><span class="text-gray-400">#{{ j.id }}</span></td>
                <td class="td">@if (j.claimId) { <a [routerLink]="['/claims', j.claimId]" class="text-primary hover:underline">claim #{{ j.claimId }}</a> }</td>
                <td class="td text-gray-700">{{ j.attempts }} / {{ j.maxAttempts }}</td>
                <td class="td text-[12px] text-destructive max-w-[360px]">{{ j.lastError }}</td>
                <td class="td text-gray-400">{{ j.updatedAt | date: 'short' }}</td>
                <td class="td text-right"><button class="btn-secondary !px-3 !py-1.5" (click)="retry(j)">Retry</button></td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
})
export class FailedJobsComponent implements OnInit {
  private work = inject(WorkApi);
  private toast = inject(ToastService);

  loading = signal(true);
  rows = signal<Job[]>([]);

  ngOnInit(): void {
    this.load();
  }

  retry(j: Job): void {
    this.work.retryJob(j.id).subscribe({
      next: () => {
        this.toast.success(`Job #${j.id} will run again now`);
        this.load();
      },
      error: (e) => this.toast.error(e),
    });
  }

  private load(): void {
    this.work.jobs().subscribe({
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
