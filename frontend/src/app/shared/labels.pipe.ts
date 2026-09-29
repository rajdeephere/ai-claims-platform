import { Pipe, PipeTransform } from '@angular/core';

/** VEHICLE_COLLISION -> Vehicle collision */
@Pipe({ name: 'label' })
export class LabelPipe implements PipeTransform {
  transform(value: unknown): string {
    if (value === null || value === undefined || value === '') {
      return '—';
    }
    const text = String(value).replace(/_/g, ' ').toLowerCase();
    return text.charAt(0).toUpperCase() + text.slice(1);
  }
}
