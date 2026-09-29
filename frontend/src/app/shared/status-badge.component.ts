import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Pastel badge pairs from the theme, keyed by the API's enum values. */
const COLORS: Record<string, string> = {
  // claim (staff)
  SUBMITTED: 'bg-[#d4effc] text-[#0b7cc4]',
  ASSESSING: 'bg-[#d4effc] text-[#0b7cc4]',
  OPEN: 'bg-[#D1FAE5] text-[#065F46]',
  AWAITING_INFO: 'bg-[#FEF3C7] text-[#92400E]',
  SIU_REVIEW: 'bg-[#EDE9FE] text-[#5B21B6]',
  CLOSED: 'bg-[#F3F4F6] text-[#374151]',
  // claim (claimant)
  RECEIVED: 'bg-[#d4effc] text-[#0b7cc4]',
  IN_REVIEW: 'bg-[#d4effc] text-[#0b7cc4]',
  ACTION_NEEDED: 'bg-[#FEF3C7] text-[#92400E]',
  PAID: 'bg-[#D1FAE5] text-[#065F46]',
  DENIED: 'bg-[#FEE2E2] text-[#991B1B]',
  WITHDRAWN: 'bg-[#F3F4F6] text-[#374151]',
  CLOSED_NO_PAYMENT: 'bg-[#F3F4F6] text-[#374151]',
  NO_PAYMENT: 'bg-[#F3F4F6] text-[#374151]',
  // money and approvals
  PENDING: 'bg-[#FEF3C7] text-[#92400E]',
  PENDING_APPROVAL: 'bg-[#FEF3C7] text-[#92400E]',
  APPROVED: 'bg-[#d4effc] text-[#0b7cc4]',
  ISSUED: 'bg-[#D1FAE5] text-[#065F46]',
  FAILED: 'bg-[#FEE2E2] text-[#991B1B]',
  REJECTED: 'bg-[#FEE2E2] text-[#991B1B]',
  CANCELLED: 'bg-[#F3F4F6] text-[#374151]',
  // documents and AI
  PENDING_UPLOAD: 'bg-[#F3F4F6] text-[#374151]',
  UPLOADED: 'bg-[#d4effc] text-[#0b7cc4]',
  PROCESSING: 'bg-[#FEF3C7] text-[#92400E]',
  PROCESSED: 'bg-[#D1FAE5] text-[#065F46]',
  COMPLETED: 'bg-[#D1FAE5] text-[#065F46]',
  PENDING_REVIEW: 'bg-[#FEF3C7] text-[#92400E]',
  ACCEPTED: 'bg-[#D1FAE5] text-[#065F46]',
  OVERRIDDEN: 'bg-[#EDE9FE] text-[#5B21B6]',
  // SIU
  CLEARED: 'bg-[#D1FAE5] text-[#065F46]',
  CONFIRMED: 'bg-[#FEE2E2] text-[#991B1B]',
  // priority and segment
  NORMAL: 'bg-[#F3F4F6] text-[#374151]',
  HIGH: 'bg-[#FEF3C7] text-[#92400E]',
  URGENT: 'bg-[#FEE2E2] text-[#991B1B]',
  FAST_TRACK: 'bg-[#D1FAE5] text-[#065F46]',
  STANDARD: 'bg-[#d4effc] text-[#0b7cc4]',
  COMPLEX: 'bg-[#EDE9FE] text-[#5B21B6]',
};

@Component({
  selector: 'app-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [class]="classes()">{{ text() }}</span>`,
})
export class StatusBadgeComponent {
  status = input.required<string | null | undefined>();

  text = computed(() => (this.status() ?? '—').replace(/_/g, ' ').toLowerCase());
  classes = computed(
    () =>
      'inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium capitalize whitespace-nowrap ' +
      (COLORS[this.status() ?? ''] ?? 'bg-[#F3F4F6] text-[#374151]'),
  );
}
