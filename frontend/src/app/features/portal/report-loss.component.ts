import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { PortalApi } from '../../core/api/claims.api';
import { LossType, PortalClaim } from '../../core/api/api.types';
import { ToastService } from '../../core/toast.service';
import { DocumentUploaderComponent } from '../../shared/document-uploader.component';
import { PageHeaderComponent } from '../../shared/page-header.component';

const today = () => new Date().toISOString().slice(0, 10);

/**
 * First notice of loss, then documents. The Idempotency-Key is made once per filled-in form: pressing
 * Submit again after a timeout returns the claim that was already created instead of filing a second one.
 */
@Component({
  selector: 'app-report-loss',
  imports: [ReactiveFormsModule, RouterLink, PageHeaderComponent, DocumentUploaderComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header title="Report a loss" subtitle="Tell us what happened. You can add documents right after." />

    <!-- steps -->
    <div class="flex items-center gap-3 mb-5 text-[13px]">
      <span class="flex items-center gap-2" [class.text-primary]="!claim()" [class.font-semibold]="!claim()">
        <span class="w-6 h-6 rounded-full flex items-center justify-center text-xs"
              [class]="claim() ? 'bg-success text-white' : 'bg-primary text-white'">{{ claim() ? '✓' : '1' }}</span>
        What happened
      </span>
      <span class="w-10 h-px bg-gray-300"></span>
      <span class="flex items-center gap-2" [class.text-primary]="claim()" [class.font-semibold]="claim()">
        <span class="w-6 h-6 rounded-full flex items-center justify-center text-xs"
              [class]="claim() ? 'bg-primary text-white' : 'bg-gray-200 text-gray-500'">2</span>
        Documents
      </span>
    </div>

    @if (!claim()) {
      <form [formGroup]="form" (ngSubmit)="submit()" class="card p-6 max-w-3xl">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label class="label" for="policy">Policy number</label>
            <input id="policy" class="input" formControlName="policyNumber" placeholder="POL-AUTO-1001" />
            <p class="text-[11px] text-gray-400 mt-1">Demo: POL-AUTO-1001 (car) or POL-HOME-2001 (home)</p>
          </div>
          <div>
            <label class="label" for="lossType">What kind of loss</label>
            <select id="lossType" class="input" formControlName="lossType">
              @for (t of lossTypes; track t.value) {
                <option [value]="t.value">{{ t.label }}</option>
              }
            </select>
          </div>
          <div>
            <label class="label" for="lossDate">When did it happen</label>
            <input id="lossDate" type="date" class="input" formControlName="lossDate" [max]="maxDate" />
          </div>
          <div>
            <label class="label" for="location">Where</label>
            <input id="location" class="input" formControlName="lossLocation" placeholder="MG Road, Bengaluru" />
          </div>
          <div class="md:col-span-2">
            <label class="label" for="description">What happened</label>
            <textarea id="description" rows="4" class="input" formControlName="description"
                      placeholder="Rear-ended at a traffic signal; the rear bumper and boot are damaged."></textarea>
          </div>
          <div>
            <label class="label" for="estimate">Your estimate of the damage (₹, optional)</label>
            <input id="estimate" type="number" min="0" step="0.01" class="input" formControlName="estimatedLoss" />
          </div>
          <div>
            <label class="label" for="phone">Phone (optional)</label>
            <input id="phone" class="input" formControlName="contactPhone" placeholder="+91 98450 12345" />
          </div>
          <label class="md:col-span-2 flex items-center gap-2 text-[13px] text-gray-700">
            <input type="checkbox" class="accent-primary" formControlName="injuriesReported" />
            Someone was injured
          </label>
        </div>
        @if (form.touched && form.invalid) {
          <p class="field-error mt-3">Please fill in the policy, type, date, place and description.</p>
        }
        <div class="flex justify-end gap-2 mt-6">
          <a routerLink="/portal/claims" class="btn-secondary">Cancel</a>
          <button class="btn-primary" [disabled]="submitting()">{{ submitting() ? 'Submitting...' : 'Submit claim' }}</button>
        </div>
      </form>
    } @else {
      <div class="card p-6 max-w-3xl">
        <div class="flex items-start gap-3 p-3 mb-5 bg-[#D1FAE5] rounded-lg">
          <span class="material-icons text-success">check_circle</span>
          <div>
            <p class="text-sm font-semibold text-[#065F46]">Claim {{ claim()!.claimNumber }} was received.</p>
            <p class="text-[13px] text-[#065F46]">Add photos, an estimate or a police report now: they help us decide faster.</p>
          </div>
        </div>
        <app-document-uploader [claimId]="claim()!.id" [portal]="true" />
        <div class="flex justify-end mt-6">
          <a [routerLink]="['/portal/claims', claim()!.id]" class="btn-primary">Go to my claim</a>
        </div>
      </div>
    }
  `,
})
export class ReportLossComponent {
  private api = inject(PortalApi);
  private toast = inject(ToastService);

  maxDate = today();
  lossTypes: { value: LossType; label: string }[] = [
    { value: 'VEHICLE_COLLISION', label: 'Car: collision' },
    { value: 'VEHICLE_THEFT', label: 'Car: theft' },
    { value: 'VEHICLE_GLASS', label: 'Car: broken glass' },
    { value: 'HOME_FIRE', label: 'Home: fire' },
    { value: 'HOME_WATER', label: 'Home: water damage' },
    { value: 'HOME_BURGLARY', label: 'Home: burglary' },
  ];

  form = inject(FormBuilder).group({
    policyNumber: ['POL-AUTO-1001', [Validators.required, Validators.maxLength(20)]],
    lossType: ['VEHICLE_COLLISION' as LossType, Validators.required],
    lossDate: [today(), Validators.required],
    lossLocation: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', [Validators.required, Validators.maxLength(2000)]],
    estimatedLoss: [null as number | null, Validators.min(0)],
    contactPhone: [''],
    injuriesReported: [false],
  });

  submitting = signal(false);
  claim = signal<PortalClaim | null>(null);
  /** one key per filled-in form; a retry reuses it */
  private idempotencyKey = crypto.randomUUID();

  constructor() {
    this.form.valueChanges.subscribe(() => (this.idempotencyKey = crypto.randomUUID()));
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.submitting.set(true);
    this.api
      .report(
        {
          policyNumber: v.policyNumber!.trim(),
          lossType: v.lossType!,
          lossDate: v.lossDate!,
          lossLocation: v.lossLocation!.trim(),
          description: v.description!.trim(),
          estimatedLoss: v.estimatedLoss ?? undefined,
          contactPhone: v.contactPhone?.trim() || undefined,
          injuriesReported: !!v.injuriesReported,
        },
        this.idempotencyKey,
      )
      .subscribe({
        next: (created) => {
          this.claim.set(created.value);
          this.submitting.set(false);
        },
        error: (e) => {
          this.toast.error(e);
          this.submitting.set(false);
        },
      });
  }
}
