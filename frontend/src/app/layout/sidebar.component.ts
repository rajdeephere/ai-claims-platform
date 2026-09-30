import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { Role } from '../core/api/api.types';
import { AuthService } from '../core/auth/auth.service';

interface NavItem {
  label: string;
  icon: string;
  route: string;
  roles: Role[];
  exact?: boolean;
}

interface NavSection {
  header: string;
  items: NavItem[];
}

const SECTIONS: NavSection[] = [
  {
    header: 'My claims',
    items: [
      { label: 'My claims', icon: 'folder_open', route: '/portal/claims', roles: ['CLAIMANT'], exact: true },
      { label: 'Report a loss', icon: 'add_circle_outline', route: '/portal/claims/new', roles: ['CLAIMANT'] },
      { label: 'Messages', icon: 'mail_outline', route: '/portal/notifications', roles: ['CLAIMANT'] },
    ],
  },
  {
    header: 'Claims handling',
    items: [
      { label: 'Dashboard', icon: 'dashboard', route: '/dashboard', roles: ['ADJUSTER', 'SUPERVISOR'] },
      { label: 'Work queue', icon: 'inventory_2', route: '/claims', roles: ['ADJUSTER', 'SUPERVISOR'] },
      { label: 'My activities', icon: 'task_alt', route: '/activities', roles: ['ADJUSTER', 'SUPERVISOR', 'SIU'] },
    ],
  },
  {
    header: 'Oversight',
    items: [
      { label: 'Approvals', icon: 'fact_check', route: '/approvals', roles: ['SUPERVISOR'] },
      { label: 'SIU cases', icon: 'policy', route: '/siu', roles: ['SUPERVISOR', 'SIU'] },
      { label: 'Failed jobs', icon: 'build_circle', route: '/ops/jobs', roles: ['SUPERVISOR'] },
    ],
  },
  {
    header: 'Help',
    items: [
      { label: 'Glossary', icon: 'menu_book', route: '/glossary', roles: ['CLAIMANT', 'ADJUSTER', 'SUPERVISOR', 'SIU'] },
    ],
  },
];

@Component({
  selector: 'app-sidebar',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <aside class="w-64 bg-white border-r border-gray-200 flex flex-col h-full">
      <div class="h-16 flex items-center px-4 border-b border-gray-200">
        <div class="flex items-center gap-2">
          <div class="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
            <span class="material-icons text-white text-[18px]">verified_user</span>
          </div>
          <div class="leading-tight">
            <span class="block text-base font-bold text-primary tracking-tight">AI Claims</span>
            <span class="block text-[10px] text-gray-400 uppercase tracking-wider">Claims platform</span>
          </div>
        </div>
      </div>

      <nav class="flex-1 py-4 px-3 space-y-5 overflow-y-auto">
        @for (section of sections(); track section.header) {
          <div>
            <p class="px-3 mb-2 text-[10px] font-semibold uppercase tracking-wider text-gray-400">{{ section.header }}</p>
            <div class="space-y-0.5">
              @for (item of section.items; track item.route) {
                <a [routerLink]="item.route" routerLinkActive="!bg-primary !text-white"
                   [routerLinkActiveOptions]="{ exact: !!item.exact }"
                   class="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors">
                  <span class="material-icons text-[20px]">{{ item.icon }}</span>
                  {{ item.label }}
                </a>
              }
            </div>
          </div>
        }
      </nav>

      @if (auth.user(); as user) {
        <div class="p-4 border-t border-gray-200">
          <div class="flex items-center gap-3">
            <div class="w-9 h-9 rounded-full bg-primary flex items-center justify-center text-white text-sm font-semibold flex-shrink-0">
              {{ initials() }}
            </div>
            <div class="min-w-0">
              <p class="text-sm font-medium text-gray-800 truncate">{{ user.displayName }}</p>
              <span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-gray-100 text-gray-600 capitalize">
                {{ user.role.toLowerCase() }}
              </span>
            </div>
          </div>
        </div>
      }
    </aside>
  `,
})
export class SidebarComponent {
  auth = inject(AuthService);

  sections = computed(() => {
    const role = this.auth.role();
    return SECTIONS.map((s) => ({ ...s, items: s.items.filter((i) => role !== null && i.roles.includes(role)) }))
      .filter((s) => s.items.length > 0);
  });

  initials = computed(() =>
    (this.auth.user()?.displayName ?? '')
      .split(' ')
      .slice(0, 2)
      .map((p) => p.charAt(0).toUpperCase())
      .join(''),
  );
}
