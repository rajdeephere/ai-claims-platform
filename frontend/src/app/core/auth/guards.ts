import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from '../api/api.types';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isAuthenticated() ? true : inject(Router).createUrlTree(['/login']);
};

/** data: { roles: [...] }. The server enforces the same rules; this only keeps people out of screens they can't use. */
export const roleGuard: CanActivateFn = (route) => {
  const auth = inject(AuthService);
  const roles = (route.data['roles'] ?? []) as Role[];
  return roles.length === 0 || auth.hasRole(...roles) ? true : inject(Router).createUrlTree(['/']);
};

/** Where each role starts. */
export const homeFor = (role: Role | null): string =>
  role === 'CLAIMANT' ? '/portal/claims' : role === 'SIU' ? '/siu' : '/dashboard';

export const homeRedirect: CanActivateFn = () => {
  const auth = inject(AuthService);
  return inject(Router).createUrlTree([auth.isAuthenticated() ? homeFor(auth.role()) : '/login']);
};
