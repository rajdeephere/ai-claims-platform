import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin, map } from 'rxjs';
import { StaffClaimsApi } from '../../core/api/claims.api';
import { WorkApi } from '../../core/api/work.api';
import { Activity, ClaimStatus, Dashboard } from '../../core/api/api.types';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { StatCardComponent } from '../../shared/stat-card.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

interface MyNumbers {
  open: number;
  awaiting: number;
  siu: number;
  activities: number;
  overdue: number;
}

/** Supervisors: the whole team (GET /dashboard/supervisor). Adjusters: their own claims and tasks. */
@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, RouterLink, StatCardComponent, StatusBadgeComponent, EmptyStateComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4">
      <div class="mb-2">
        <h1 class="text-[22px] font-bold text-primary">Dashboard</h1>
        <p class="text-[13.5px] text-gray-500 mt-1">Welcome back, {{ auth.user()?.displayName }}.</p>
      </div>

      @if (team(); as d) {
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <a routerLink="/claims"><app-stat-card label="Open claims" [value]="active(d)" /></a>
          <a routerLink="/approvals"><app-stat-card label="Pending approvals" [value]="d.pendingApprovals" valueColor="#D97706" /></a>
          <a routerLink="/siu"><app-stat-card label="Open SIU cases" [value]="d.openSiuCases" valueColor="#7C3AED" /></a>
          <a routerLink="/activities"><app-stat-card label="SLA breaches" [value]="d.breachedActivities"
             [valueColor]="d.breachedActivities ? '#DC2626' : '#059669'" [hint]="d.openActivities + ' open activities'" /></a>
        </div>

        <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
          <div class="card p-5 lg:col-span-2">
            <h3 class="text-sm font-bold text-primary mb-4">Claims by status</h3>
            <div class="space-y-2.5">
              @for (s of statuses; track s) {
                <div class="flex items-center gap-3 text-[13px]">
                  <span class="w-32"><app-status-badge [status]="s" /></span>
                  <div class="flex-1 h-2.5 bg-gray-100 rounded-full overflow-hidden">
                    <div class="h-full bg-primary rounded-full" [style.width.%]="share(d, s)"></div>
                  </div>
                  <span class="w-10 text-right font-semibold text-gray-700">{{ d.claimsByStatus[s] ?? 0 }}</span>
                </div>
              }
            </div>
            @if (d.unassignedClaims) {
              <p class="mt-4 text-[13px] text-warning font-medium">{{ d.unassignedClaims }} claim(s) have no adjuster.</p>
            }
          </div>
          <div class="card p-5">
            <h3 class="text-sm font-bold text-primary mb-4">SLA breaches by owner</h3>
            @for (o of d.breachesByOwner; track o.owner) {
              <div class="flex justify-between py-1.5 text-[13px] border-b border-gray-100 last:border-0">
                <span class="text-gray-700">{{ ownerLabel(o.owner) }}</span>
                <span class="font-semibold text-destructive">{{ o.count }}</span>
              </div>
            } @empty {
              <p class="text-[13px] text-success">Nothing is overdue.</p>
            }
          </div>
        </div>
      }

      @if (mine(); as m) {
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <a [routerLink]="['/claims']" [queryParams]="{ status: 'OPEN' }"><app-stat-card label="My open claims" [value]="m.open" /></a>
          <a [routerLink]="['/claims']" [queryParams]="{ status: 'AWAITING_INFO' }"><app-stat-card label="Waiting for claimants" [value]="m.awaiting" valueColor="#D97706" /></a>
          <a [routerLink]="['/claims']" [queryParams]="{ status: 'SIU_REVIEW' }"><app-stat-card label="Under SIU review" [value]="m.siu" valueColor="#7C3AED" /></a>
          <a routerLink="/activities"><app-stat-card label="My activities" [value]="m.activities"
             [valueColor]="m.overdue ? '#DC2626' : '#059669'" [hint]="m.overdue + ' overdue'" /></a>
        </div>
      }

      <div class="card overflow-hidden">
        <div class="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
          <h3 class="text-sm font-bold text-primary">Next up</h3>
          <a routerLink="/activities" class="text-[13px] text-primary font-semibold hover:underline">All activities</a>
        </div>
        @for (a of next(); track a.id) {
          <a [routerLink]="['/claims', a.claimId]" class="flex items-center gap-3 px-5 py-3 border-b border-gray-100 hover:bg-[#f8fafc]">
            <app-status-badge [status]="a.priority" />
            <span class="flex-1 text-[13.5px] text-gray-800">{{ a.subject }}</span>
            <span class="text-[13px] font-semibold text-primary">{{ a.claimNumber }}</span>
            <span class="text-[12px] w-40 text-right" [class]="overdue(a) ? 'text-destructive font-semibold' : 'text-gray-400'">
              due {{ a.dueAt | date: 'short' }}
            </span>
          </a>
        } @empty {
          <app-empty-state icon="task_alt" message="No open activities. Nice." />
        }
      </div>
    </div>
  `,
})
export class DashboardComponent implements OnInit {
  auth = inject(AuthService);
  private work = inject(WorkApi);
  private claims = inject(StaffClaimsApi);
  private toast = inject(ToastService);

  statuses: ClaimStatus[] = ['SUBMITTED', 'ASSESSING', 'OPEN', 'AWAITING_INFO', 'SIU_REVIEW', 'CLOSED'];
  team = signal<Dashboard | null>(null);
  mine = signal<MyNumbers | null>(null);
  next = signal<Activity[]>([]);

  ngOnInit(): void {
    if (this.auth.hasRole('SUPERVISOR')) {
      this.work.dashboard().subscribe({ next: (d) => this.team.set(d), error: (e) => this.toast.error(e) });
    } else {
      const count = (status: ClaimStatus) => this.claims.queue({ status, size: 1 }).pipe(map((p) => p.totalElements));
      forkJoin({
        open: count('OPEN'),
        awaiting: count('AWAITING_INFO'),
        siu: count('SIU_REVIEW'),
        activities: this.work.myActivities(false, 0, 1).pipe(map((p) => p.totalElements)),
        overdue: this.work.myActivities(true, 0, 1).pipe(map((p) => p.totalElements)),
      }).subscribe({ next: (m) => this.mine.set(m), error: (e) => this.toast.error(e) });
    }
    this.work.myActivities(false, 0, 6).subscribe((p) => this.next.set(p.content));
  }

  active(d: Dashboard): number {
    return this.statuses.filter((s) => s !== 'CLOSED').reduce((sum, s) => sum + (d.claimsByStatus[s] ?? 0), 0);
  }

  share(d: Dashboard, status: ClaimStatus): number {
    const max = Math.max(1, ...this.statuses.map((s) => d.claimsByStatus[s] ?? 0));
    return ((d.claimsByStatus[status] ?? 0) / max) * 100;
  }

  /** "queue:SUPERVISOR" -> "Supervisor queue" */
  ownerLabel(owner: string | undefined): string {
    if (!owner?.startsWith('queue:')) {
      return owner ?? '';
    }
    const role = owner.slice(6).toLowerCase();
    return role.charAt(0).toUpperCase() + role.slice(1) + ' queue';
  }

  overdue(a: Activity): boolean {
    return new Date(a.dueAt).getTime() < Date.now();
  }
}
