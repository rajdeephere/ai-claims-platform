import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DocumentCategory } from '../core/api/api.types';
import { DocumentsApi } from '../core/api/documents.api';
import { errorMessage } from '../core/http/api-errors';

interface Item {
  file: File;
  state: 'waiting' | 'uploading' | 'done' | 'duplicate' | 'failed' | 'invalid';
  error?: string;
}

const MAX_BYTES = 10 * 1024 * 1024;
const TYPES = ['application/pdf', 'image/jpeg', 'image/png'];

/**
 * Pick files, choose what they are, upload. Each file: presigned URL -> PUT to storage -> complete.
 * The browser's checks (type, 10 MB) are for the user's convenience; the server checks the real bytes.
 */
@Component({
  selector: 'app-document-uploader',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="border-2 border-dashed border-gray-300 rounded-lg p-5 text-center hover:border-primary transition-colors"
         (dragover)="$event.preventDefault()" (drop)="drop($event)">
      <span class="material-icons text-gray-400 text-4xl">cloud_upload</span>
      <p class="text-sm text-gray-600 mt-1">Drop PDF, JPEG or PNG files here, or
        <label class="text-primary font-semibold cursor-pointer hover:underline">
          browse<input type="file" multiple accept=".pdf,.jpg,.jpeg,.png" class="hidden" (change)="pick($event)" />
        </label>
      </p>
      <p class="text-[11px] text-gray-400 mt-1">Up to 10 MB each</p>
    </div>

    @if (items().length) {
      <div class="mt-3 flex items-center gap-3">
        <label class="text-xs font-semibold text-gray-700" for="category">These files are</label>
        <select id="category" class="input !w-auto" [(ngModel)]="category">
          @for (c of categories; track c.value) {
            <option [value]="c.value">{{ c.label }}</option>
          }
        </select>
        <button class="btn-primary ml-auto" [disabled]="busy() || !pending()" (click)="uploadAll()">
          <span class="material-icons text-[16px]">upload</span>{{ busy() ? 'Uploading...' : 'Upload' }}
        </button>
      </div>
      <ul class="mt-3 divide-y divide-gray-100 border border-gray-100 rounded-lg">
        @for (item of items(); track item.file) {
          <li class="flex items-center gap-3 px-3 py-2 text-[13px]">
            <span class="material-icons text-[18px] text-gray-400">{{ item.file.type === 'application/pdf' ? 'picture_as_pdf' : 'image' }}</span>
            <span class="flex-1 truncate text-gray-700">{{ item.file.name }}</span>
            <span class="text-gray-400">{{ (item.file.size / 1024).toFixed(0) }} KB</span>
            @switch (item.state) {
              @case ('uploading') { <span class="text-warning font-medium">uploading</span> }
              @case ('done') { <span class="text-success font-medium">uploaded</span> }
              @case ('duplicate') { <span class="text-gray-500 font-medium">already on this claim</span> }
              @case ('failed') { <span class="text-destructive font-medium">failed</span> }
              @case ('invalid') { <button class="text-gray-400 hover:text-destructive" (click)="remove(item)"><span class="material-icons text-[18px]">close</span></button> }
              @default { <button class="text-gray-400 hover:text-destructive" (click)="remove(item)"><span class="material-icons text-[18px]">close</span></button> }
            }
          </li>
          @if (item.error) {
            <li class="px-3 pb-2 text-xs text-destructive">{{ item.error }}</li>
          }
        }
      </ul>
    }
  `,
})
export class DocumentUploaderComponent {
  private documents = inject(DocumentsApi);

  claimId = input.required<number>();
  portal = input(false);
  uploaded = output<void>();

  categories: { value: DocumentCategory; label: string }[] = [
    { value: 'DAMAGE_PHOTO', label: 'Photos of the damage' },
    { value: 'REPAIR_ESTIMATE', label: 'A repair estimate' },
    { value: 'INVOICE', label: 'An invoice' },
    { value: 'POLICE_REPORT', label: 'A police report' },
    { value: 'MEDICAL_REPORT', label: 'A medical report' },
    { value: 'OTHER', label: 'Something else' },
  ];
  category: DocumentCategory = 'DAMAGE_PHOTO';

  items = signal<Item[]>([]);
  busy = signal(false);
  pending = () => this.items().some((i) => i.state === 'waiting' || i.state === 'failed');

  pick(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.add(Array.from(input.files ?? []));
    input.value = '';
  }

  drop(event: DragEvent): void {
    event.preventDefault();
    this.add(Array.from(event.dataTransfer?.files ?? []));
  }

  remove(item: Item): void {
    this.items.update((list) => list.filter((i) => i !== item));
  }

  async uploadAll(): Promise<void> {
    this.busy.set(true);
    for (const item of this.items().filter((i) => i.state === 'waiting' || i.state === 'failed')) {
      this.set(item, { state: 'uploading', error: undefined });
      await new Promise<void>((resolve) =>
        this.documents.upload(this.claimId(), item.file, this.category, this.portal()).subscribe({
          next: (result) => this.set(item, { state: result.duplicate ? 'duplicate' : 'done' }),
          error: (e) => {
            this.set(item, { state: 'failed', error: errorMessage(e) });
            resolve();
          },
          complete: () => resolve(),
        }),
      );
    }
    this.busy.set(false);
    this.uploaded.emit();
  }

  private add(files: File[]): void {
    const items = files.map<Item>((file) => {
      if (!TYPES.includes(file.type)) {
        return { file, state: 'invalid', error: 'Only PDF, JPEG and PNG files can be uploaded' };
      }
      if (file.size > MAX_BYTES) {
        return { file, state: 'invalid', error: 'The file is larger than 10 MB' };
      }
      return { file, state: 'waiting' };
    });
    this.items.update((list) => [...list, ...items]);
  }

  private set(item: Item, change: Partial<Item>): void {
    Object.assign(item, change);
    this.items.update((list) => [...list]);   // same items, new array: the view re-renders
  }
}
