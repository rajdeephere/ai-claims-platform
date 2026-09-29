import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PortalApi } from '../../core/api/claims.api';
import { Notification } from '../../core/api/api.types';
import { ToastService } from '../../core/toast.service';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { PageHeaderComponent } from '../../shared/page-header.component';
import { SkeletonComponent } from '../../shared/skeleton.component';

@Component({
  selector: 'app-notifications',
  imports: [DatePipe, RouterLink, PageHeaderComponent, EmptyStateComponent, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="Messages" subtitle="Updates about your claims" />
    <div class="card overflow-hidden max-w-3xl">
      @if (loading()) {
        <app-skeleton />
      } @else if (items().length === 0) {
        <app-empty-state icon="mail_outline" message="No messages yet." />
      } @else {
        <ul class="divide-y divide-gray-100">
          @for (n of items(); track n.id) {
            <li class="px-5 py-4 flex gap-3" [class.bg-blue-50/50]="!n.readAt">
              <span class="material-icons text-[18px] mt-0.5" [class]="n.readAt ? 'text-gray-300' : 'text-[#0b7cc4]'">
                {{ n.readAt ? 'drafts' : 'mail' }}
              </span>
              <div class="flex-1 min-w-0">
                <p class="text-sm font-semibold text-gray-800">{{ n.subject }}</p>
                <p class="text-[13px] text-gray-600 mt-0.5">{{ n.body }}</p>
                <p class="text-[11px] text-gray-400 mt-1">
                  {{ n.createdAt | date: 'medium' }}
                  @if (n.claimId) {
                    · <a [routerLink]="['/portal/claims', n.claimId]" class="text-primary hover:underline">view claim</a>
                  }
                </p>
              </div>
              @if (!n.readAt) {
                <button class="text-xs text-[#0b7cc4] hover:underline self-start" (click)="markRead(n)">Mark read</button>
              }
            </li>
          }
        </ul>
      }
    </div>
  `,
})
export class NotificationsComponent implements OnInit {
  private api = inject(PortalApi);
  private toast = inject(ToastService);

  loading = signal(true);
  items = signal<Notification[]>([]);

  ngOnInit(): void {
    this.api.notifications(0, 50).subscribe({
      next: (p) => {
        this.items.set(p.content);
        this.loading.set(false);
      },
      error: (e) => {
        this.toast.error(e);
        this.loading.set(false);
      },
    });
  }

  markRead(n: Notification): void {
    this.api.markRead(n.id).subscribe((updated) =>
      this.items.update((list) => list.map((i) => (i.id === updated.id ? updated : i))),
    );
  }
}
