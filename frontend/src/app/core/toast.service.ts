import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { errorMessage } from './http/api-errors';

@Injectable({ providedIn: 'root' })
export class ToastService {
  private snackBar = inject(MatSnackBar);

  success(message: string): void {
    this.open(message, 'success-snackbar', 4000);
  }

  info(message: string): void {
    this.open(message, 'info-snackbar', 4000);
  }

  error(errorOrMessage: unknown): void {
    const message = typeof errorOrMessage === 'string' ? errorOrMessage : errorMessage(errorOrMessage);
    this.open(message, 'error-snackbar', 7000);
  }

  private open(message: string, panelClass: string, duration: number): void {
    this.snackBar.open(message, 'Close', {
      duration,
      horizontalPosition: 'end',
      verticalPosition: 'top',
      panelClass: [panelClass],
    });
  }
}
