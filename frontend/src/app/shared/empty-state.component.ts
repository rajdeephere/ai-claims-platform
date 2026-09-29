import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="py-14 text-center">
      <span class="material-icons text-gray-300 text-5xl mb-3 block">{{ icon() }}</span>
      <p class="text-gray-500 text-sm">{{ message() }}</p>
      <ng-content />
    </div>
  `,
})
export class EmptyStateComponent {
  icon = input('inbox');
  message = input.required<string>();
}
