import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { PortalApi } from '../../core/api/claims.api';
import { PortalClaimSummary } from '../../core/api/api.types';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { LabelPipe } from '../../shared/labels.pipe';
import { PageHeaderComponent } from '../../shared/page-header.component';
import { SkeletonComponent } from '../../shared/skeleton.component';
import { StatusBadgeComponent } from '../../shared/status-badge.component';

@Component({
  selector: 'app-my-claims',
  imports: [RouterLink, DatePipe, PageHeaderComponent, StatusBadgeComponent, EmptyStateComponent, SkeletonComponent, LabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="My claims" subtitle="Everything you have reported, newest first">
      <a routerLink="/portal/claims/new" class="btn-primary"><span class="material-icons text-[16px]">add</span>Report a loss</a>
    </app-page-header>

    <div class="card overflow-hidden">
      @if (loading()) {
        <app-skeleton />
      } @else if (claims().length === 0) {
        <app-empty-state icon="folder_open" message="You haven't reported a loss yet.">
          <a routerLink="/portal/claims/new" class="mt-3 inline-block text-primary text-sm font-semibold hover:underline">Report your first loss</a>
        </app-empty-state>
      } @else {
        <table class="w-full">
          <thead>
            <tr>
              <th class="th">Claim</th>
              <th class="th">Loss</th>
              <th class="th">Loss date</th>
              <th class="th">Status</th>
              <th class="th">Reported</th>
            </tr>
          </thead>
          <tbody>
            @for (claim of claims(); track claim.id) {
              <tr class="cursor-pointer hover:bg-[#f8fafc] transition-colors" (click)="open(claim)">
                <td class="td font-semibold text-primary">{{ claim.claimNumber }}</td>
                <td class="td text-gray-700">{{ claim.lossType | label }}</td>
                <td class="td text-gray-700">{{ claim.lossDate | date: 'mediumDate' }}</td>
                <td class="td">
                  <app-status-badge [status]="claim.status" />
                  @if (claim.actionNeeded) {
                    <span class="ml-2 text-xs font-semibold text-warning">Your answer is needed</span>
                  }
                </td>
                <td class="td text-gray-400">{{ claim.submittedAt | date: 'medium' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
})
export class MyClaimsComponent implements OnInit {
  private api = inject(PortalApi);
  private router = inject(Router);
  private toast = inject(ToastService);

  loading = signal(true);
  claims = signal<PortalClaimSummary[]>([]);

  ngOnInit(): void {
    this.api.myClaims(0, 100).subscribe({
      next: (page) => {
        this.claims.set(page.content);
        this.loading.set(false);
      },
      error: (e) => {
        this.toast.error(e);
        this.loading.set(false);
      },
    });
  }

  open(claim: PortalClaimSummary): void {
    this.router.navigate(['/portal/claims', claim.id]);
  }
}
