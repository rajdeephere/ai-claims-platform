import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const withToken = (req: HttpRequest<unknown>, token: string | null) =>
  token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

/**
 * Only calls to our API get the token and a correlation ID; a presigned storage URL must never see the
 * bearer token. On 401 the token is refreshed once (single-flight) and the request retried; if the
 * refresh fails, the session is over.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  if (!auth.isApiUrl(req.url)) {
    return next(req);
  }
  const traced = req.clone({ setHeaders: { 'X-Correlation-Id': crypto.randomUUID() } });
  if (auth.isAuthCall(req.url)) {
    return next(traced);
  }
  return next(withToken(traced, auth.token())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
        return throwError(() => error);
      }
      return auth.refresh().pipe(
        catchError(() => auth.sessionEnded()),
        switchMap((token) => next(withToken(traced, token))),
      );
    }),
  );
};
