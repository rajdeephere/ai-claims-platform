import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { catchError, exhaustMap, map, of, takeWhile, timeout, timer } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { DEMO_PASSWORD, DEMO_USERS, DemoUser } from '../../core/auth/demo-users';
import { homeFor } from '../../core/auth/guards';
import { ToastService } from '../../core/toast.service';

type ServerState = 'checking' | 'waking' | 'ready' | 'down';

const REPO = 'https://github.com/rajdeephere/ai-claims-platform';

interface Feature {
  icon: string;
  title: string;
  text: string;
}

const FEATURES: Feature[] = [
  {
    icon: 'route',
    title: 'The whole claim lifecycle',
    text: 'First notice of loss, policy check, triage, assignment, exposures, reserves, payment and closure, as a state machine in the domain: any move the rules don\'t allow is refused.',
  },
  {
    icon: 'account_balance',
    title: 'Money with real controls',
    text: 'Per-user authority limits, maker-checker approvals (nobody approves their own request), a row lock against overspending, and idempotent payments that can never pay twice.',
  },
  {
    icon: 'auto_awesome',
    title: 'AI that suggests, people decide',
    text: 'An LLM reads repair estimates and damage photos. Its answers are validated like any untrusted input, and adjusters accept or correct them with a reason.',
  },
  {
    icon: 'policy',
    title: 'Explainable fraud score and SIU',
    text: 'Deterministic rules plus AI signals, each with its reason. High scores go to investigators; payments are held, and the claimant is never told.',
  },
  {
    icon: 'schedule',
    title: 'Workflow without an engine',
    text: 'Timers, retries, SLAs and escalations run on a PostgreSQL job queue with a transactional outbox, in the same transaction as the change: no Camunda, no drift.',
  },
  {
    icon: 'verified',
    title: 'Built to be trusted',
    text: 'An append-only audit trail, a contract-tested API, module boundaries checked by ArchUnit, and close to 300 automated tests against real Postgres and S3.',
  },
];

const STACK = ['Java 17', 'Spring Boot 3.5', 'PostgreSQL', 'Flyway', 'Angular 21', 'Tailwind', 'Groq LLM',
  'S3 storage', 'Testcontainers', 'ArchUnit', 'Docker', 'GitHub Actions'];

/**
 * The public front door: what this is, why it is interesting, one-click tours by role. Static, so it loads
 * instantly from Vercel; meanwhile it pings the API, which also wakes it if free hosting put it to sleep.
 */
@Component({
  selector: 'app-landing',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="min-h-screen bg-[#f9fafb]">
      <!-- top bar -->
      <header class="bg-white border-b border-gray-200">
        <div class="max-w-6xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <div class="w-8 h-8 rounded-lg bg-primary flex items-center justify-center">
              <span class="material-icons text-white text-[18px]">verified_user</span>
            </div>
            <span class="text-base font-bold text-primary tracking-tight">AI Claims</span>
          </div>
          <nav class="flex items-center gap-2 sm:gap-4 text-[13px]">
            <a href="#try" class="hidden sm:inline text-gray-600 hover:text-primary">Try it</a>
            <a href="#how" class="hidden sm:inline text-gray-600 hover:text-primary">How it works</a>
            <a routerLink="/glossary" class="text-gray-600 hover:text-primary">Glossary</a>
            <a [href]="repo" target="_blank" rel="noopener" class="text-gray-600 hover:text-primary">GitHub</a>
            <a routerLink="/login" class="btn-primary">Sign in</a>
          </nav>
        </div>
      </header>

      <!-- hero -->
      <section class="max-w-6xl mx-auto px-4 sm:px-6 pt-14 pb-12">
        <div class="max-w-3xl">
          <p class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-accent/10 text-accent text-xs font-semibold mb-5">
            <span class="material-icons text-[14px]">school</span> A learning project, fully working
          </p>
          <h1 class="text-[32px] sm:text-[42px] leading-tight font-bold text-primary">
            An insurance claims platform, from first notice of loss to payment
          </h1>
          <p class="text-[16px] text-gray-600 mt-4 leading-relaxed">
            Built the way enterprise claims systems work: exposures, reserves, authority limits and maker-checker
            approvals, with an AI assistant that reads the claim documents and people who make every decision.
          </p>
          <div class="flex flex-wrap items-center gap-3 mt-7">
            <a href="#try" class="btn-primary !px-5 !py-2.5 !text-[14px]">
              <span class="material-icons text-[18px]">play_arrow</span>Try the demo
            </a>
            <a [href]="repo" target="_blank" rel="noopener" class="btn-secondary !px-5 !py-2.5 !text-[14px]">
              <span class="material-icons text-[18px]">code</span>View the code
            </a>
            <span class="inline-flex items-center gap-2 text-[13px]" [class]="stateClass()">
              <span class="w-2 h-2 rounded-full" [class]="dotClass()"></span>{{ stateText() }}
            </span>
          </div>
        </div>
      </section>

      <!-- features -->
      <section class="max-w-6xl mx-auto px-4 sm:px-6 pb-14">
        <h2 class="text-xs font-bold text-gray-500 uppercase tracking-wide mb-4">What it demonstrates</h2>
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          @for (f of features; track f.title) {
            <div class="card p-5">
              <div class="w-9 h-9 rounded-lg bg-primary/5 flex items-center justify-center mb-3">
                <span class="material-icons text-primary text-[20px]">{{ f.icon }}</span>
              </div>
              <h3 class="text-[15px] font-semibold text-primary mb-1.5">{{ f.title }}</h3>
              <p class="text-[13.5px] text-gray-600 leading-relaxed">{{ f.text }}</p>
            </div>
          }
        </div>
      </section>

      <section class="max-w-6xl mx-auto px-4 sm:px-6 pb-14 -mt-6">
        <a routerLink="/glossary" class="card p-5 flex items-center gap-4 hover:border-primary transition-colors">
          <span class="material-icons text-accent text-[28px]">menu_book</span>
          <span class="flex-1">
            <span class="block text-[15px] font-semibold text-primary">New to insurance?</span>
            <span class="block text-[13.5px] text-gray-600">Exposure, reserve, authority limit, maker-checker, subrogation, SIU: the glossary explains every term the app uses, and how it applies it.</span>
          </span>
          <span class="material-icons text-gray-400">arrow_forward</span>
        </a>
      </section>

      <!-- try it -->
      <section id="try" class="bg-white border-y border-gray-200 scroll-mt-4">
        <div class="max-w-6xl mx-auto px-4 sm:px-6 py-14">
          <h2 class="text-[24px] font-bold text-primary mb-1">Try it as any role</h2>
          <p class="text-[14px] text-gray-600 mb-6">One click signs you in with a demo account. Each role sees different screens and may do different things.</p>
          <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            @for (u of demoUsers; track u.username) {
              <div class="card p-5 flex flex-col">
                <div class="flex items-center gap-2 mb-2">
                  <span class="material-icons text-accent text-[22px]">{{ u.icon }}</span>
                  <h3 class="text-[16px] font-semibold text-primary">{{ u.role }}</h3>
                </div>
                <p class="text-[13.5px] text-gray-600 leading-relaxed flex-1">{{ u.tour }}</p>
                <button class="btn-primary mt-4 w-full" [disabled]="signingIn() !== null" (click)="tryAs(u)">
                  @if (signingIn() === u.username) {
                    <span class="material-icons animate-spin text-[16px]">progress_activity</span>Signing in...
                  } @else {
                    Try as {{ u.role }}
                  }
                </button>
              </div>
            }
          </div>

          <div class="mt-8 p-5 rounded-lg bg-[#f9fafb] border border-gray-200">
            <h3 class="text-[14px] font-semibold text-primary mb-2 flex items-center gap-1.5">
              <span class="material-icons text-[18px]">tour</span>A five-minute tour
            </h3>
            <ol class="text-[13.5px] text-gray-700 space-y-1.5 list-decimal list-inside">
              <li><b>Claimant:</b> report a loss on <code>POL-AUTO-1001</code> and upload a PDF estimate or a photo.</li>
              <li><b>Supervisor:</b> open the work queue; the claim was triaged and assigned automatically. Look at the AI result and fraud score.</li>
              <li><b>Adjuster</b> (the assigned one, <code>adjuster1</code> or <code>adjuster2</code>): open Financials, create an exposure with a ₹4,000 reserve and pay ₹3,800. Within your authority, it is issued in seconds.</li>
              <li><b>Maker-checker:</b> raise the reserve to ₹15,000. Above the adjuster's ₹5,000 limit it waits; sign in as <b>Supervisor</b> and approve it in Approvals.</li>
              <li><b>Adjuster:</b> close the exposure and the claim. The <b>Claimant</b> now sees <b>Paid</b>, with a message about the payment.</li>
            </ol>
            <p class="text-[12px] text-gray-500 mt-3">
              Demo accounts share the password <code>{{ demoPassword }}</code>. Everyone uses the same demo data:
              please don't upload real personal documents.
            </p>
          </div>
        </div>
      </section>

      <!-- how it works -->
      <section id="how" class="max-w-6xl mx-auto px-4 sm:px-6 py-14 scroll-mt-4">
        <h2 class="text-[24px] font-bold text-primary mb-1">How it's built</h2>
        <p class="text-[14px] text-gray-600 mb-6">A modular monolith: one Spring Boot API owns all claim state; everything runs on free tiers.</p>

        <div class="flex flex-col lg:flex-row items-stretch gap-3">
          @for (box of architecture; track box.title; let last = $last) {
            <div class="card p-4 flex-1">
              <p class="text-[11px] font-bold text-gray-500 uppercase tracking-wide">{{ box.where }}</p>
              <p class="text-[15px] font-semibold text-primary mt-0.5">{{ box.title }}</p>
              <p class="text-[12.5px] text-gray-600 mt-1 leading-relaxed">{{ box.text }}</p>
            </div>
            @if (!last) {
              <div class="flex items-center justify-center text-gray-400">
                <span class="material-icons rotate-90 lg:rotate-0">arrow_forward</span>
              </div>
            }
          }
        </div>

        <div class="flex flex-wrap gap-2 mt-6">
          @for (s of stack; track s) {
            <span class="px-2.5 py-1 rounded-full bg-white border border-gray-200 text-[12px] text-gray-700">{{ s }}</span>
          }
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-3 gap-4 mt-8">
          <a [href]="repo + '/tree/main/docs/adr'" target="_blank" rel="noopener" class="card p-4 hover:border-primary transition-colors">
            <p class="text-[14px] font-semibold text-primary flex items-center gap-1.5"><span class="material-icons text-[18px]">gavel</span>30 decision records</p>
            <p class="text-[12.5px] text-gray-600 mt-1">Why no workflow engine, how money is kept safe, how the AI is contained.</p>
          </a>
          <a [href]="repo + '/blob/main/docs/architecture.md'" target="_blank" rel="noopener" class="card p-4 hover:border-primary transition-colors">
            <p class="text-[14px] font-semibold text-primary flex items-center gap-1.5"><span class="material-icons text-[18px]">account_tree</span>Architecture</p>
            <p class="text-[12.5px] text-gray-600 mt-1">Modules, request flow, job queue and outbox, AI pipeline, diagrams.</p>
          </a>
          <a [href]="apiDocs" target="_blank" rel="noopener" class="card p-4 hover:border-primary transition-colors">
            <p class="text-[14px] font-semibold text-primary flex items-center gap-1.5"><span class="material-icons text-[18px]">api</span>API documentation</p>
            <p class="text-[12.5px] text-gray-600 mt-1">The live OpenAPI contract the UI's types are generated from.</p>
          </a>
        </div>
      </section>

      <footer class="border-t border-gray-200 bg-white">
        <div class="max-w-6xl mx-auto px-4 sm:px-6 py-6 flex flex-col sm:flex-row gap-2 justify-between text-[12px] text-gray-500">
          <span>A learning project. Not affiliated with any insurer.</span>
          <span>Demo data only.</span>
        </div>
      </footer>
    </div>
  `,
})
export class LandingComponent implements OnInit {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  private router = inject(Router);
  private toast = inject(ToastService);
  private destroyRef = inject(DestroyRef);

  repo = REPO;
  apiDocs = environment.apiUrl + '/swagger-ui.html';
  features = FEATURES;
  stack = STACK;
  demoUsers = DEMO_USERS;
  demoPassword = DEMO_PASSWORD;
  architecture = [
    { where: 'Vercel', title: 'Angular web app', text: 'Portal for claimants and a workspace for staff; buttons come from what the server allows.' },
    { where: 'Render · Singapore', title: 'Spring Boot API', text: 'State machine, money rules, job queue, outbox and audit trail, in one transaction per step.' },
    { where: 'Supabase · Singapore', title: 'PostgreSQL + S3 storage', text: 'All claim state and timers in the database; documents uploaded straight from the browser.' },
    { where: 'Groq', title: 'LLM', text: 'Reads estimates and photos in the background; validated, never decides.' },
  ];

  state = signal<ServerState>('checking');
  signingIn = signal<string | null>(null);

  ngOnInit(): void {
    const started = Date.now();
    // ask every 5 s until the API answers; after 5 s of silence tell the visitor it is waking up.
    // exhaustMap, not switchMap: a waking server can take a minute to answer one request, so a check
    // still in flight must be waited for, never cancelled by the next tick.
    timer(0, 5000)
      .pipe(
        exhaustMap(() =>
          this.http.get<{ status: string }>(`${environment.apiUrl}/actuator/health`).pipe(
            timeout(90000),
            map((h) => h.status === 'UP'),
            catchError(() => of(false)),
          ),
        ),
        takeWhile((up) => !up && Date.now() - started < 240_000, true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((up) => {
        if (up) {
          this.state.set('ready');
        } else if (Date.now() - started >= 240_000) {
          this.state.set('down');
        } else if (Date.now() - started > 5000) {
          this.state.set('waking');
        }
      });
  }

  stateText(): string {
    switch (this.state()) {
      case 'ready':
        return 'Demo server ready';
      case 'waking':
        return 'Demo server waking up (free hosting sleeps when idle; up to a few minutes)';
      case 'down':
        return 'Demo server not answering; please try again later';
      default:
        return 'Checking the demo server...';
    }
  }

  stateClass(): string {
    return this.state() === 'ready' ? 'text-success' : this.state() === 'down' ? 'text-destructive' : 'text-gray-500';
  }

  dotClass(): string {
    return this.state() === 'ready' ? 'bg-success' : this.state() === 'down' ? 'bg-destructive' : 'bg-warning animate-pulse';
  }

  async tryAs(user: DemoUser): Promise<void> {
    this.signingIn.set(user.username);
    try {
      await this.auth.login(user.username, DEMO_PASSWORD);
      await this.router.navigateByUrl(homeFor(this.auth.role()));
    } catch (e) {
      this.toast.error(this.state() === 'ready' ? e : 'The demo server is still waking up; try again in a moment.');
    } finally {
      this.signingIn.set(null);
    }
  }
}
