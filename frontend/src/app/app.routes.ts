import { Routes } from '@angular/router';
import { authGuard, homeRedirect, roleGuard } from './core/auth/guards';
import { LayoutComponent } from './layout/layout.component';

const STAFF = ['ADJUSTER', 'SUPERVISOR', 'SIU'];
const HANDLERS = ['ADJUSTER', 'SUPERVISOR'];

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/auth/login.component').then((m) => m.LoginComponent) },
  {
    path: '',
    component: LayoutComponent,
    canActivate: [authGuard],
    children: [
      { path: '', pathMatch: 'full', canActivate: [homeRedirect], children: [] },
      {
        path: 'portal',
        canActivate: [roleGuard],
        data: { roles: ['CLAIMANT'] },
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'claims' },
          {
            path: 'claims',
            data: { title: 'My claims' },
            children: [
              { path: '', loadComponent: () => import('./features/portal/my-claims.component').then((m) => m.MyClaimsComponent) },
              {
                path: 'new',
                data: { title: 'Report a loss' },
                loadComponent: () => import('./features/portal/report-loss.component').then((m) => m.ReportLossComponent),
              },
              {
                path: ':id',
                data: { title: 'Claim' },
                loadComponent: () => import('./features/portal/portal-claim.component').then((m) => m.PortalClaimComponent),
              },
            ],
          },
          {
            path: 'notifications',
            data: { title: 'Messages' },
            loadComponent: () => import('./features/portal/notifications.component').then((m) => m.NotificationsComponent),
          },
        ],
      },
      {
        path: 'dashboard',
        canActivate: [roleGuard],
        data: { roles: HANDLERS, title: 'Dashboard' },
        loadComponent: () => import('./features/staff/dashboard.component').then((m) => m.DashboardComponent),
      },
      {
        path: 'claims',
        canActivate: [roleGuard],
        data: { roles: STAFF, title: 'Work queue' },
        children: [
          { path: '', loadComponent: () => import('./features/staff/work-queue.component').then((m) => m.WorkQueueComponent) },
          {
            path: ':id',
            data: { title: 'Claim' },
            loadComponent: () => import('./features/staff/claim/claim-workspace.component').then((m) => m.ClaimWorkspaceComponent),
          },
        ],
      },
      {
        path: 'activities',
        canActivate: [roleGuard],
        data: { roles: STAFF, title: 'Activities' },
        loadComponent: () => import('./features/staff/activities.component').then((m) => m.ActivitiesComponent),
      },
      {
        path: 'approvals',
        canActivate: [roleGuard],
        data: { roles: ['SUPERVISOR'], title: 'Approvals' },
        loadComponent: () => import('./features/staff/approvals.component').then((m) => m.ApprovalsComponent),
      },
      {
        path: 'siu',
        canActivate: [roleGuard],
        data: { roles: ['SIU', 'SUPERVISOR'], title: 'SIU cases' },
        children: [
          { path: '', loadComponent: () => import('./features/siu/siu-queue.component').then((m) => m.SiuQueueComponent) },
          {
            path: ':id',
            data: { title: 'Case file' },
            loadComponent: () => import('./features/siu/siu-case.component').then((m) => m.SiuCaseComponent),
          },
        ],
      },
      {
        path: 'ops/jobs',
        canActivate: [roleGuard],
        data: { roles: ['SUPERVISOR'], title: 'Failed jobs' },
        loadComponent: () => import('./features/staff/failed-jobs.component').then((m) => m.FailedJobsComponent),
      },
      { path: '**', redirectTo: '' },
    ],
  },
];
