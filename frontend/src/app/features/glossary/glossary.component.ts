import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { homeFor } from '../../core/auth/guards';
import { GLOSSARY, TermGroup } from './glossary.data';

/** Public: the insurance words the app uses, what they mean, and how the app applies them. */
@Component({
  selector: 'app-glossary',
  imports: [FormsModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="min-h-screen bg-[#f9fafb]">
      <header class="bg-white border-b border-gray-200 sticky top-0 z-10">
        <div class="max-w-5xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          <a routerLink="/" class="flex items-center gap-2">
            <div class="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
              <span class="material-icons text-white text-[18px]">verified_user</span>
            </div>
            <span class="text-base font-bold text-primary tracking-tight">AI Claims</span>
          </a>
          @if (auth.isAuthenticated()) {
            <a [routerLink]="home()" class="btn-primary"><span class="material-icons text-[16px]">arrow_back</span>Back to the app</a>
          } @else {
            <a routerLink="/login" class="btn-primary">Sign in</a>
          }
        </div>
      </header>

      <main class="max-w-5xl mx-auto px-4 sm:px-6 py-10">
        <a routerLink="/" class="inline-flex items-center gap-1 text-[13px] text-gray-500 hover:text-primary mb-4">
          <span class="material-icons text-[18px]">arrow_back</span>Back to home
        </a>
        <h1 class="text-[28px] sm:text-[34px] font-bold text-primary">Insurance glossary</h1>
        <p class="text-[15px] text-gray-600 mt-2 max-w-3xl">
          The words you'll meet in this app, what they mean in insurance, and how the app applies them.
          New to claims? Read <b>The claim</b> and <b>Money</b> first.
        </p>

        <div class="mt-6 flex flex-col sm:flex-row gap-3 sm:items-center">
          <div class="relative sm:w-80">
            <span class="material-icons absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-[18px]">search</span>
            <input class="input !pl-9" placeholder="Search, e.g. reserve" [ngModel]="query()" (ngModelChange)="query.set($event)"
                   aria-label="Search the glossary" />
          </div>
          <div class="flex flex-wrap gap-1.5">
            @for (g of groups; track g.id) {
              <!-- buttons, not href="#id": with <base href="/"> a fragment link would go to "/" (the landing page) -->
              <button type="button" (click)="jumpTo(g.id)"
                      class="px-3 py-1 rounded-full bg-white border border-gray-300 text-[12.5px] text-gray-700 hover:border-primary">{{ g.title }}</button>
            }
          </div>
        </div>

        @for (g of filtered(); track g.id) {
          <section [id]="g.id" class="mt-10 scroll-mt-24">
            <h2 class="text-[18px] font-bold text-primary flex items-center gap-2 mb-3">
              <span class="material-icons text-accent text-[22px]">{{ g.icon }}</span>{{ g.title }}
            </h2>
            <div class="space-y-3">
              @for (t of g.terms; track t.term) {
                <article class="card p-5">
                  <h3 class="text-[16px] font-semibold text-primary">
                    {{ t.term }}
                    @if (t.aka) { <span class="text-[13px] font-normal text-gray-500">· {{ t.aka }}</span> }
                  </h3>
                  <p class="text-[14px] text-gray-800 mt-1.5 leading-relaxed">{{ t.meaning }}</p>
                  <p class="text-[13.5px] text-gray-600 mt-2 leading-relaxed">
                    <span class="text-[11px] font-bold uppercase tracking-wide text-accent mr-1">In this app</span>{{ t.inThisApp }}
                  </p>
                  @if (t.seeIt) {
                    <p class="text-[12.5px] text-gray-500 mt-1.5 flex items-start gap-1">
                      <span class="material-icons text-[15px] mt-[1px]">visibility</span>{{ t.seeIt }}
                    </p>
                  }
                </article>
              }
            </div>
          </section>
        } @empty {
          <p class="mt-10 text-[14px] text-gray-500">No term matches "{{ query() }}".</p>
        }
      </main>

      <footer class="border-t border-gray-200 bg-white">
        <div class="max-w-5xl mx-auto px-4 sm:px-6 py-6 text-[12px] text-gray-500">
          A learning project. Definitions are simplified for a demo; real policies and regulations vary.
        </div>
      </footer>
    </div>
  `,
})
export class GlossaryComponent {
  auth = inject(AuthService);

  groups = GLOSSARY;
  query = signal('');
  home = computed(() => homeFor(this.auth.role()));

  /** Scroll to a group; clear the search first so the group is on the page. */
  jumpTo(id: string): void {
    this.query.set('');
    setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  filtered = computed<TermGroup[]>(() => {
    const q = this.query().trim().toLowerCase();
    if (!q) {
      return this.groups;
    }
    return this.groups
      .map((g) => ({
        ...g,
        terms: g.terms.filter((t) =>
          [t.term, t.aka ?? '', t.meaning, t.inThisApp].some((text) => text.toLowerCase().includes(q)),
        ),
      }))
      .filter((g) => g.terms.length > 0);
  });
}
