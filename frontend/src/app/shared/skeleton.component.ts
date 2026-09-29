import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'app-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="p-6 space-y-3">
      @for (i of rowsArray(); track i) {
        <div class="h-11 bg-gray-100 rounded animate-pulse"></div>
      }
    </div>
  `,
})
export class SkeletonComponent {
  rows = input(4);
  rowsArray = () => Array.from({ length: this.rows() }, (_, i) => i);
}
