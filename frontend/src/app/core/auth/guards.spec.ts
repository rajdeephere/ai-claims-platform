import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Me } from '../api/api.types';
import { AuthService } from './auth.service';
import { homeFor, landingGuard } from './guards';

describe('landingGuard', () => {
  const user = (role: Me['role']): Me => ({ id: 1, username: 'u', displayName: 'U', role, authorityLimit: 0 });

  function run(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      landingGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  it('shows the landing page to visitors', () => {
    expect(run()).toBe(true);
  });

  it('sends signed-in users to their own start page instead', () => {
    const auth = TestBed.inject(AuthService);
    for (const role of ['CLAIMANT', 'ADJUSTER', 'SUPERVISOR', 'SIU'] as const) {
      auth.user.set(user(role));
      const result = run();
      expect(result).toBeInstanceOf(UrlTree);
      expect((result as UrlTree).toString()).toBe(homeFor(role));
    }
  });
});
