import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { WorkApi } from '../../../core/api/work.api';
import { Activity } from '../../../core/api/api.types';
import { ToastService } from '../../../core/toast.service';
import { LabelPipe } from '../../../shared/labels.pipe';
import { askReason } from '../../../shared/reason-dialog.component';
import { StatusBadgeComponent } from '../../../shared/status-badge.component';

/** Every task on the claim, open and closed, with who owned it and whether its SLA was breached. */
@Component({
  selector: 'app-claim-activities',
  imports: [DatePipe, StatusBadgeComponent, LabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card overflow-hidden">
      <table class="w-full">
        <thead><tr>
          <th class="th">Activity</th><th class="th">Owner</th><th class="th">Priority</th><th class="th">Due</th><th class="th">Status</th><th class="th"></th>
        </tr></thead>
        <tbody>
          @for (a of activities(); track a.id) {
            <tr>
              <td class="td"><span class="text-gray-800">{{ a.subject }}</span><br /><span class="text-[11px] text-gray-400">{{ a.type | label }}</span></td>
              <td class="td text-gray-700">{{ a.assignee ?? ((a.candidateRole | label) + ' queue') }}</td>
              <td class="td"><app-status-badge [status]="a.priority" /></td>
              <td class="td text-[13px]" [class]="a.escalatedAt ? 'text-destructive font-semibold' : 'text-gray-500'">
                {{ a.dueAt | date: 'short' }}@if (a.escalatedAt) { <br /><span class="text-[11px]">SLA breached</span> }
              </td>
              <td class="td"><app-status-badge [status]="a.status" />
                @if (a.outcomeNote) { <br /><span class="text-[11px] text-gray-500">{{ a.outcomeNote }}</span> }</td>
              <td class="td text-right">
                @if (a.status === 'OPEN' && a.completedByPerson) {
                  <button class="text-[12px] text-primary font-semibold hover:underline" (click)="complete(a)">Complete</button>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="td text-center text-gray-400">No activities.</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class ClaimActivitiesComponent implements OnInit {
  private work = inject(WorkApi);
  private dialog = inject(MatDialog);
  private toast = inject(ToastService);

  claimId = input.required<number>();
  activities = signal<Activity[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.work.claimActivities(this.claimId()).subscribe((a) => this.activities.set(a));
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
}
