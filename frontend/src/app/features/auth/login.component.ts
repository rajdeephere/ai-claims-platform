import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { homeFor } from '../../core/auth/guards';
import { apiError } from '../../core/http/api-errors';

/** The demo users seeded by Flyway (db/demo); shown so a visitor can try each role. */
const DEMO_USERS = [
  { username: 'claimant1', role: 'Claimant', hint: 'reports a loss, answers questions' },
  { username: 'adjuster1', role: 'Adjuster', hint: 'handles claims, pays up to ₹5,000' },
  { username: 'supervisor1', role: 'Supervisor', hint: 'approves, reassigns, watches SLAs' },
  { username: 'siu1', role: 'SIU', hint: 'investigates referrals' },
];

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="min-h-screen flex items-center justify-center bg-[#f9fafb] px-4 py-10">
      <div class="w-full max-w-[420px]">
        <div class="bg-white rounded-lg border border-gray-200 py-11 px-10">
          <div class="text-center mb-8">
            <div class="flex items-center justify-center gap-2.5 mb-3.5">
              <div class="w-9 h-9 rounded-lg bg-primary flex items-center justify-center text-white">
                <span class="material-icons text-[20px]">verified_user</span>
              </div>
              <span class="text-xl font-bold text-primary">AI Claims</span>
            </div>
            <h2 class="text-lg font-semibold text-primary mb-1">Sign in to your account</h2>
            <p class="text-[13px] text-gray-500">Insurance claims, from first notice of loss to payment</p>
          </div>

          @if (expired()) {
            <div class="p-3 mb-4 bg-amber-50 border border-amber-200 rounded-lg text-sm text-amber-800">
              Your session ended. Please sign in again.
            </div>
          }

          <form [formGroup]="form" (ngSubmit)="submit()" class="flex flex-col gap-4">
            <div>
              <label for="username" class="label">Username</label>
              <input id="username" formControlName="username" autocomplete="username" class="input !py-2.5 !px-3.5"
                     placeholder="adjuster1" />
              @if (form.controls.username.touched && form.controls.username.invalid) {
                <p class="field-error">Username is required</p>
              }
            </div>
            <div>
              <label for="password" class="label">Password</label>
              <input id="password" type="password" formControlName="password" autocomplete="current-password"
                     class="input !py-2.5 !px-3.5" placeholder="........" />
              @if (form.controls.password.touched && form.controls.password.invalid) {
                <p class="field-error">Password is required</p>
              }
            </div>

            @if (error()) {
              <div class="p-3 bg-red-50 border border-red-200 rounded-lg">
                <p class="text-sm text-destructive">{{ error() }}</p>
              </div>
            }

            <button type="submit" [disabled]="loading()"
                    class="w-full py-2.5 bg-primary text-white rounded-md text-[15px] font-medium hover:bg-gray-800 focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed transition-all">
              @if (loading()) {
                <span class="inline-flex items-center justify-center gap-2">
                  <span class="material-icons animate-spin text-[18px]">progress_activity</span>
                  Signing in...
                </span>
              } @else {
                Sign In
              }
            </button>
          </form>

          <div class="mt-6 pt-5 border-t border-gray-200">
            <p class="text-[11.5px] text-gray-400 text-center mb-3">Demo accounts (password <code>Password1!</code>)</p>
            <div class="grid grid-cols-2 gap-2">
              @for (demo of demoUsers; track demo.username) {
                <button type="button" (click)="useDemo(demo.username)"
                        class="text-left border border-gray-200 rounded-md px-3 py-2 hover:border-primary hover:bg-gray-50 transition-colors">
                  <span class="block text-[13px] font-semibold text-primary">{{ demo.role }}</span>
                  <span class="block text-[11px] text-gray-500 leading-tight">{{ demo.hint }}</span>
                </button>
              }
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);

  /** ?expired=1 after a refresh failed (bound from the query string) */
  expired = input<string>();

  demoUsers = DEMO_USERS;
  loading = signal(false);
  error = signal('');

  form = inject(FormBuilder).nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  useDemo(username: string): void {
    this.form.setValue({ username, password: 'Password1!' });
    this.submit();
  }

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    this.error.set('');
    try {
      const { username, password } = this.form.getRawValue();
      await this.auth.login(username, password);
      await this.router.navigateByUrl(homeFor(this.auth.role()));
    } catch (e) {
      const err = apiError(e);
      this.error.set(
        err.status === 401
          ? 'Wrong username or password.'
          : err.status === 429
            ? 'Too many failed attempts. Wait a minute and try again.'
            : (err.message ?? 'Sign-in failed'),
      );
    } finally {
      this.loading.set(false);
    }
  }
}
