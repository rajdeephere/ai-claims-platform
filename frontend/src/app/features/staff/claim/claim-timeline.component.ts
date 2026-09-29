import { DatePipe, JsonPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { StaffClaimsApi } from '../../../core/api/claims.api';
import { StaffClaim, TimelineEntry } from '../../../core/api/api.types';
import { ToastService } from '../../../core/toast.service';
import { LabelPipe } from '../../../shared/labels.pipe';

/** The append-only audit trail and internal notes, oldest first (ADR-0013). Notes never reach the claimant. */
@Component({
  selector: 'app-claim-timeline',
  imports: [DatePipe, JsonPipe, FormsModule, LabelPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
      <div class="card p-5 lg:col-span-2">
        <ol class="relative border-l border-gray-200 ml-2">
          @for (e of entries(); track $index) {
            <li class="ml-5 pb-4">
              <span class="absolute -left-[7px] mt-1 w-3.5 h-3.5 rounded-full border-2 border-white"
                    [class]="e.kind === 'NOTE' ? 'bg-accent' : e.action === 'SLA_BREACHED' ? 'bg-destructive' : 'bg-primary'"></span>
              <p class="text-[13px]">
                <b class="text-gray-800">{{ e.action | label }}</b>
                <span class="text-gray-400"> · {{ e.actor }} · {{ e.at | date: 'medium' }}</span>
              </p>
              @if (e.text) { <p class="text-[13px] text-gray-700 mt-0.5 whitespace-pre-line">{{ e.text }}</p> }
              @if (e.before || e.after) {
                <p class="text-[11px] text-gray-400 mt-0.5 font-mono break-all">
                  @if (e.before) { {{ e.before | json }} → } {{ e.after | json }}
                </p>
              }
            </li>
          }
        </ol>
      </div>
      <div class="card p-5 self-start">
        <h3 class="text-sm font-bold text-primary mb-2">Add a note</h3>
        <p class="text-[12px] text-gray-500 mb-2">Internal: staff only, never shown to the claimant.</p>
        <textarea rows="5" class="input" [(ngModel)]="note" maxlength="4000"></textarea>
        <div class="flex justify-end mt-2">
          <button class="btn-primary" [disabled]="!note.trim()" (click)="addNote()">Add note</button>
        </div>
      </div>
    </div>
  `,
})
export class ClaimTimelineComponent implements OnInit {
  private api = inject(StaffClaimsApi);
  private toast = inject(ToastService);

  claim = input.required<StaffClaim>();
  entries = signal<TimelineEntry[]>([]);
  note = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.timeline(this.claim().id).subscribe((t) => this.entries.set(t));
  }

  addNote(): void {
    this.api.addNote(this.claim().id, this.note.trim()).subscribe({
      next: () => {
        this.note = '';
        this.load();
      },
      error: (e) => this.toast.error(e),
    });
  }
}
