import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { WorkApi } from '../../core/api/work.api';
import { Activity, Page } from '../../core/api/api.types';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { LabelPipe } from '../../shared/labels.pipe';
import { PageHeaderComponent } from '../../shared/page-header.component';
import { askReason } from '../../shared/reason-dialog.component';
import { SkeletonComponent } from '../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

type View = 'mine' | 'overdue' | 'breached';

/** My tasks (mine and my role's queue), soonest due first; supervisors also see every SLA breach. */
@Component({
  selector: 'app-activities',
  imports: [DatePipe, RouterLink, PageHeaderComponent, StatusBadgeComponent, EmptyStateComponent, SkeletonComponent, LabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="Activities" subtitle="Tasks with a due time; anything late is escalated to supervisors" />

    <div class="flex gap-2 mb-4">
      @for (v of views; track v.id) {
        @if (v.id !== 'breached' || auth.hasRole('SUPERVISOR')) {
          <button class="px-3.5 py-1.5 rounded-full text-[13px] font-medium border transition-colors"
                  [class]="view() === v.id ? 'bg-primary text-white border-primary' : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'"
                  (click)="show(v.id)">{{ v.label }}</button>
        }
      }
    </div>

    <div class="card overflow-hidden">
      @if (loading()) {
        <app-skeleton />
      } @else if (rows().length === 0) {
        <app-empty-state icon="task_alt" message="Nothing here." />
      } @else {
        <table class="w-full">
          <thead><tr>
            <th class="th">Activity</th><th class="th">Claim</th><th class="th">Owner</th><th class="th">Priority</th><th class="th">Due</th><th class="th"></th>
          </tr></thead>
          <tbody>
            @for (a of rows(); track a.id) {
              <tr class="hover:bg-[#f8fafc]">
                <td class="td"><span class="text-gray-800">{{ a.subject }}</span><br /><span class="text-[11px] text-gray-400">{{ a.type | label }}</span></td>
                <td class="td"><a [routerLink]="['/claims', a.claimId]" class="font-semibold text-primary hover:underline">{{ a.claimNumber }}</a></td>
                <td class="td text-gray-700">{{ a.assignee ?? ((a.candidateRole | label) + ' queue') }}</td>
                <td class="td"><app-status-badge [status]="a.priority" /></td>
                <td class="td text-[13px]" [class]="isLate(a) ? 'text-destructive font-semibold' : 'text-gray-500'">{{ a.dueAt | date: 'short' }}</td>
                <td class="td text-right">
                  @if (a.completedByPerson) {
                    <button class="text-[12px] text-primary font-semibold hover:underline" (click)="complete(a)">Complete</button>
                  } @else {
                    <span class="text-[11px] text-gray-400">closes with the outcome</span>
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
export class ActivitiesComponent implements OnInit {
  auth = inject(AuthService);
  private work = inject(WorkApi);
  private dialog = inject(MatDialog);
  private toast = inject(ToastService);

  views: { id: View; label: string }[] = [
    { id: 'mine', label: 'Open' },
    { id: 'overdue', label: 'Overdue' },
    { id: 'breached', label: 'All SLA breaches' },
  ];
  view = signal<View>('mine');
  loading = signal(true);
  rows = signal<Activity[]>([]);

  ngOnInit(): void {
    this.load();
  }

  show(view: View): void {
    this.view.set(view);
    this.load();
  }

  isLate(a: Activity): boolean {
    return new Date(a.dueAt).getTime() < Date.now();
  }

  complete(a: Activity): void {
    askReason(this.dialog, { title: 'Complete activity', message: a.subject, label: 'What was done (optional)',
      confirmText: 'Complete', required: false })
      .subscribe((note) => note !== undefined && this.work.completeActivity(a.id, note).subscribe({
        next: () => {
          this.toast.success('Activity completed');
          this.load();
        },
        error: (e) => this.toast.error(e),
      }));
  }

  private load(): void {
    this.loading.set(true);
    const source: Observable<Page<Activity>> =
      this.view() === 'breached' ? this.work.breachedActivities() : this.work.myActivities(this.view() === 'overdue');
    source.subscribe({
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
