import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-stat-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bg-white border border-[#e5e7eb] rounded-lg p-5 h-full">
      <p class="text-xs font-bold text-gray-500 uppercase tracking-wide">{{ label() }}</p>
      <p class="text-[28px] font-bold mt-1" [style.color]="valueColor()">{{ value() }}</p>
      @if (hint()) {
        <p class="text-xs mt-1 text-gray-500">{{ hint() }}</p>
      }
    </div>
  `,
})
export class StatCardComponent {
  label = input.required<string>();
  value = input.required<string | number>();
  valueColor = input('#111827');
  hint = input('');
}
