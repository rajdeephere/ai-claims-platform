import { HttpErrorResponse } from '@angular/common/http';
import { ApiError } from '../api/api.types';

/** The server's ApiError (stable code + message, ADR-0009), or a readable fallback. */
export function apiError(error: unknown): ApiError & { status: number } {
  if (error instanceof HttpErrorResponse) {
    const body = error.error as ApiError | null;
    if (body && typeof body === 'object' && body.code) {
      return { ...body, status: error.status };
    }
    if (error.status === 0) {
      return { status: 0, code: 'NETWORK', message: 'The server could not be reached. It may be waking up; try again in a moment.' };
    }
    return { status: error.status, code: 'HTTP_' + error.status, message: error.message };
  }
  return { status: -1, code: 'CLIENT', message: String(error) };
}

export function errorMessage(error: unknown): string {
  const e = apiError(error);
  if (e.status === 412) {
    return 'Someone changed this in the meantime. The latest version has been loaded; please check and try again.';
  }
  if (e.violations?.length) {
    return e.violations.map((v) => `${v.field}: ${v.message}`).join('; ');
  }
  return e.message ?? 'Something went wrong';
}
