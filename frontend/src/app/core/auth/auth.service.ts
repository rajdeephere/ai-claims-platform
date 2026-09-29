import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, catchError, finalize, firstValueFrom, map, of, shareReplay, switchMap, tap, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Me, Role, TokenResponse } from '../api/api.types';

const REFRESH_KEY = 'aiclaims.refresh';

/**
 * Tokens (ADR-0029): the 15-minute access token lives only in memory; the rotating refresh token in
 * sessionStorage, so a reload keeps the session but closing the tab ends it. Refreshing is single-flight:
 * refresh tokens are one-time (reuse is treated as theft and revokes the session, ADR-0004), so two
 * parallel 401s must share one refresh call, never make two.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);
  private api = environment.apiUrl + '/api/v1';

  private accessToken = signal<string | null>(null);
  private refreshing: Observable<string> | null = null;

  readonly user = signal<Me | null>(null);
  readonly isAuthenticated = computed(() => this.user() !== null);
  readonly role = computed<Role | null>(() => this.user()?.role ?? null);
  readonly isStaff = computed(() => {
    const role = this.role();
    return role !== null && role !== 'CLAIMANT';
  });

  token(): string | null {
    return this.accessToken();
  }

  hasRole(...roles: Role[]): boolean {
    const role = this.role();
    return role !== null && roles.includes(role);
  }

  isApiUrl(url: string): boolean {
    return url.startsWith(this.api);
  }

  isAuthCall(url: string): boolean {
    return url.startsWith(this.api + '/auth/');
  }

  async login(username: string, password: string): Promise<void> {
    const tokens = await firstValueFrom(
      this.http.post<TokenResponse>(`${this.api}/auth/login`, { username, password }),
    );
    this.store(tokens);
    await this.loadMe();
  }

  /** At start-up: a refresh token from this tab's session means we can resume without a login. */
  async restore(): Promise<void> {
    if (!sessionStorage.getItem(REFRESH_KEY)) {
      return;
    }
    try {
      await firstValueFrom(this.refresh());
      await this.loadMe();
    } catch {
      this.clear();
    }
  }

  /** One refresh at a time; concurrent callers get the same result. */
  refresh(): Observable<string> {
    if (!this.refreshing) {
      const refreshToken = sessionStorage.getItem(REFRESH_KEY);
      if (!refreshToken) {
        return throwError(() => new Error('no session'));
      }
      this.refreshing = this.http.post<TokenResponse>(`${this.api}/auth/refresh`, { refreshToken }).pipe(
        tap((tokens) => this.store(tokens)),
        map((tokens) => tokens.accessToken),
        finalize(() => (this.refreshing = null)),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    }
    return this.refreshing;
  }

  logout(): void {
    const refreshToken = sessionStorage.getItem(REFRESH_KEY);
    this.clear();
    this.router.navigate(['/login']);
    if (refreshToken) {
      // revoke server-side; the local session is gone either way
      this.http.post(`${this.api}/auth/logout`, { refreshToken }).pipe(catchError(() => of(null))).subscribe();
    }
  }

  /** The refresh failed: the session is over (expired, revoked, or reused elsewhere). */
  sessionEnded(): Observable<never> {
    this.clear();
    this.router.navigate(['/login'], { queryParams: { expired: 1 } });
    return throwError(() => new Error('session ended'));
  }

  private loadMe(): Promise<void> {
    return firstValueFrom(
      this.http.get<Me>(`${this.api}/me`).pipe(
        tap((me) => this.user.set(me)),
        switchMap(() => of(undefined)),
      ),
    );
  }

  private store(tokens: TokenResponse): void {
    this.accessToken.set(tokens.accessToken);
    sessionStorage.setItem(REFRESH_KEY, tokens.refreshToken);
  }

  private clear(): void {
    this.accessToken.set(null);
    this.user.set(null);
    sessionStorage.removeItem(REFRESH_KEY);
  }
}
