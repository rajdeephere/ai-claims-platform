import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, interval, map, startWith, switchMap, catchError, of } from 'rxjs';
import { PortalApi } from '../core/api/claims.api';
import { WorkApi } from '../core/api/work.api';
import { AuthService } from '../core/auth/auth.service';

interface Crumb {
  label: string;
  path: string;
}

/** Breadcrumbs and title from the routes' data.title; a counter of what needs attention. */
@Component({
  selector: 'app-topbar',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="h-16 bg-white border-b border-gray-200 flex items-center justify-between px-6">
      <div>
        <p class="text-[11px] text-gray-400 -mb-0.5">
          @for (crumb of crumbs(); track crumb.path; let last = $last) {
            @if (!last) {
              <a [routerLink]="crumb.path" class="hover:text-gray-600">{{ crumb.label }}</a>
              <span class="mx-1">/</span>
            } @else {
              <span class="text-gray-500">{{ crumb.label }}</span>
            }
          }
        </p>
        <h1 class="text-lg font-semibold text-gray-800">{{ title() }}</h1>
      </div>

      <div class="flex items-center gap-3">
        <a [routerLink]="attentionLink()" class="relative p-2 rounded-lg hover:bg-gray-100 transition-colors"
           [title]="attentionTitle()">
          <span class="material-icons text-[20px] text-gray-500">notifications</span>
          @if (attention() > 0) {
            <span class="absolute -top-0.5 -right-0.5 min-w-[18px] h-[18px] px-1 rounded-full bg-red-500 text-white text-[10px] font-semibold flex items-center justify-center">
              {{ attention() > 99 ? '99+' : attention() }}
            </span>
          }
        </a>
        @if (auth.user(); as user) {
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-gray-700">{{ user.displayName }}</span>
            <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-accent/10 text-accent capitalize">
              {{ user.role.toLowerCase() }}
            </span>
          </div>
        }
        <button (click)="auth.logout()"
                class="flex items-center gap-1 px-3 py-1.5 text-sm text-gray-500 hover:text-destructive hover:bg-red-50 rounded-lg transition-colors">
          <span class="material-icons text-[18px]">logout</span>
          Logout
        </button>
      </div>
    </header>
  `,
})
export class TopbarComponent implements OnInit {
  auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private portal = inject(PortalApi);
  private work = inject(WorkApi);
  private destroyRef = inject(DestroyRef);

  attention = signal(0);

  private navigation = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      startWith(null),
      map(() => this.trail()),
    ),
    { initialValue: [] as Crumb[] },
  );

  crumbs = computed(() => [{ label: 'Home', path: '/' }, ...this.navigation()]);
  title = computed(() => this.navigation().at(-1)?.label ?? 'AI Claims');
  attentionLink = computed(() => (this.auth.role() === 'CLAIMANT' ? '/portal/notifications' : '/activities'));
  attentionTitle = computed(() => (this.auth.role() === 'CLAIMANT' ? 'Unread messages' : 'My open activities'));

  ngOnInit(): void {
    // a light poll: claimants see unread messages, staff their open activities
    interval(30_000)
      .pipe(
        startWith(0),
        switchMap(() =>
          (this.auth.role() === 'CLAIMANT'
            ? this.portal.unreadCount().pipe(map((r) => r.unread))
            : this.work.myActivities(false, 0, 1).pipe(map((p) => p.totalElements))
          ).pipe(catchError(() => of(this.attention()))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((n) => this.attention.set(n));
  }

  private trail(): Crumb[] {
    const crumbs: Crumb[] = [];
    let route = this.route.snapshot.root;
    let path = '';
    while (route.firstChild) {
      route = route.firstChild;
      const segment = route.url.map((u) => u.path).join('/');
      if (segment) {
        path += '/' + segment;
      }
      const title = route.data['title'] as string | undefined;
      if (title && crumbs.at(-1)?.label !== title) {
        crumbs.push({ label: title, path: path || '/' });
      }
    }
    return crumbs;
  }
}
