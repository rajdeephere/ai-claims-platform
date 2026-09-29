import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { WorkApi } from '../../../core/api/work.api';

export interface ReassignResult {
  adjusterId: number;
  reason: string;
}

@Component({
  selector: 'app-reassign-dialog',
  imports: [MatDialogModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title class="!text-lg !font-semibold !text-primary">Reassign claim</h2>
    <mat-dialog-content>
      <label class="label" for="adjuster">New adjuster</label>
      <select id="adjuster" class="input mb-3" [(ngModel)]="adjusterId">
        @for (a of adjusters(); track a.id) {
          <option [ngValue]="a.id" [disabled]="a.id === data.currentId">{{ a.displayName }} ({{ a.username }})</option>
        }
      </select>
      <label class="label" for="why">Reason</label>
      <textarea id="why" rows="3" class="input" [(ngModel)]="reason" placeholder="Workload, expertise, leave..."></textarea>
    </mat-dialog-content>
    <mat-dialog-actions align="end" class="!pt-4">
      <button class="px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-100 rounded-lg" (click)="ref.close()">Cancel</button>
      <button class="btn-accent ml-2" [disabled]="!adjusterId || !reason.trim()"
              (click)="ref.close({ adjusterId: adjusterId!, reason: reason.trim() })">Reassign</button>
    </mat-dialog-actions>
  `,
})
export class ReassignDialogComponent implements OnInit {
  private work = inject(WorkApi);
  data = inject<{ currentId: number | null }>(MAT_DIALOG_DATA);
  ref = inject<MatDialogRef<ReassignDialogComponent, ReassignResult>>(MatDialogRef);

  adjusters = signal<{ id: number; username: string; displayName: string }[]>([]);
  adjusterId: number | null = null;
  reason = '';

  ngOnInit(): void {
    this.work.adjusters().subscribe((list) => this.adjusters.set(list));
  }
}
