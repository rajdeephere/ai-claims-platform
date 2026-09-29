import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-wrap items-center justify-between gap-4 mb-5">
      <div>
        <h1 class="text-[22px] font-bold text-primary mb-0.5">{{ title() }}</h1>
        @if (subtitle()) {
          <p class="text-[13.5px] text-gray-500">{{ subtitle() }}</p>
        }
      </div>
      <div class="flex items-center gap-2"><ng-content /></div>
    </div>
  `,
})
export class PageHeaderComponent {
  title = input.required<string>();
  subtitle = input('');
}
