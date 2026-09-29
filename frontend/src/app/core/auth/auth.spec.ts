import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { environment } from '../../../environments/environment';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

const API = environment.apiUrl + '/api/v1';

const tokens = (access: string, refresh: string) => ({
  accessToken: access,
  refreshToken: refresh,
  tokenType: 'Bearer',
  expiresIn: 900,
  accessTokenExpiresAt: '',
  refreshTokenExpiresAt: '',
  user: { id: 3, username: 'adjuster1', displayName: 'Meera Nair', role: 'ADJUSTER' },
});

describe('AuthService and authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', children: [] }]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => backend.verify());

  const settle = () => new Promise((resolve) => setTimeout(resolve));

  async function loggedIn(): Promise<void> {
    const done = auth.login('adjuster1', 'secret');
    backend.expectOne(`${API}/auth/login`).flush(tokens('access-1', 'refresh-1'));
    await settle();   // /me is asked for once the login promise has resolved
    backend.expectOne(`${API}/me`).flush({ id: 3, username: 'adjuster1', displayName: 'Meera Nair', role: 'ADJUSTER', authorityLimit: 5000 });
    await done;
  }

  it('sends the access token and a correlation id to the API', async () => {
    await loggedIn();
    http.get(`${API}/claims`).subscribe();

    const req = backend.expectOne(`${API}/claims`);
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(req.request.headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/);
    req.flush({});
  });

  it('never sends the token to a presigned storage URL', async () => {
    await loggedIn();
    http.put('http://localhost:8333/aiclaims-documents/abc?X-Amz-Signature=x', new Blob(['%PDF'])).subscribe();

    const req = backend.expectOne((r) => r.url.startsWith('http://localhost:8333'));
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null);
  });

  it('refreshes once for two parallel 401s and retries both (refresh tokens are one-time)', async () => {
    await loggedIn();
    const results: string[] = [];
    http.get<string>(`${API}/claims/1`).subscribe((r) => results.push(r));
    http.get<string>(`${API}/claims/2`).subscribe((r) => results.push(r));

    backend.expectOne(`${API}/claims/1`).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne(`${API}/claims/2`).flush(null, { status: 401, statusText: 'Unauthorized' });

    // exactly one refresh, with the stored refresh token
    const refresh = backend.expectOne(`${API}/auth/refresh`);
    expect(refresh.request.body).toEqual({ refreshToken: 'refresh-1' });
    refresh.flush(tokens('access-2', 'refresh-2'));

    const retries = backend.match((r) => r.url.startsWith(`${API}/claims/`));
    expect(retries).toHaveLength(2);
    retries.forEach((r) => {
      expect(r.request.headers.get('Authorization')).toBe('Bearer access-2');
      r.flush('ok');
    });
    expect(results).toEqual(['ok', 'ok']);
    expect(sessionStorage.getItem('aiclaims.refresh')).toBe('refresh-2');
  });

  it('ends the session when the refresh is refused', async () => {
    await loggedIn();
    let failed = false;
    http.get(`${API}/claims`).subscribe({ error: () => (failed = true) });

    backend.expectOne(`${API}/claims`).flush(null, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne(`${API}/auth/refresh`).flush({ code: 'REFRESH_TOKEN_REUSED' }, { status: 401, statusText: 'Unauthorized' });

    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem('aiclaims.refresh')).toBeNull();
  });

  it('does not try to refresh when the login itself fails', () => {
    let failed = false;
    auth.login('adjuster1', 'wrong').catch(() => (failed = true));

    backend.expectOne(`${API}/auth/login`).flush({ code: 'BAD_CREDENTIALS' }, { status: 401, statusText: 'Unauthorized' });
    backend.expectNone(`${API}/auth/refresh`);
    return settle().then(() => expect(failed).toBe(true));
  });
});
