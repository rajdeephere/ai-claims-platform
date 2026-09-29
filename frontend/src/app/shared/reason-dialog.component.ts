import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { Observable } from 'rxjs';

export interface ReasonDialogData {
  title: string;
  message?: string;
  label?: string;
  placeholder?: string;
  confirmText?: string;
  danger?: boolean;
  /** false: the text is optional (e.g. an approval comment) */
  required?: boolean;
}

/**
 * "Why?" for every state change: the API wants a reason (and audits it), so the UI always asks.
 * Styled like the theme's confirm dialog.
 */
@Component({
  selector: 'app-reason-dialog',
  imports: [MatDialogModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title class="!text-lg !font-semibold !text-primary">{{ data.title }}</h2>
    <mat-dialog-content>
      @if (data.message) {
        <p class="text-sm text-gray-600 mb-3">{{ data.message }}</p>
      }
      <label class="label" for="reason">{{ data.label ?? 'Reason' }}</label>
      <textarea id="reason" rows="4" class="input" [(ngModel)]="text" [placeholder]="data.placeholder ?? ''"
                maxlength="2000"></textarea>
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="!pt-4">
      <button class="px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100 rounded-lg" (click)="ref.close()">
        Cancel
      </button>
      <button [class]="data.danger ? 'btn-danger ml-2' : 'btn-accent ml-2'"
              [disabled]="data.required !== false && !text.trim()" (click)="ref.close(text.trim())">
        {{ data.confirmText ?? 'Confirm' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class ReasonDialogComponent {
  data = inject<ReasonDialogData>(MAT_DIALOG_DATA);
  ref = inject<MatDialogRef<ReasonDialogComponent, string>>(MatDialogRef);
  text = '';
}

/** Opens the dialog; emits the text, or undefined if cancelled. */
export function askReason(dialog: MatDialog, data: ReasonDialogData): Observable<string | undefined> {
  return dialog.open<ReasonDialogComponent, ReasonDialogData, string>(ReasonDialogComponent, { data, width: '480px' })
    .afterClosed();
}
