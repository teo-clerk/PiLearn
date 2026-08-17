import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  HostListener,
  PLATFORM_ID,
  computed,
  inject,
  output,
  signal,
} from '@angular/core';

interface TourStep {
  title: string;
  body: string;
  icon: string;
}

const STEPS: readonly TourStep[] = [
  {
    icon: '🎹',
    title: 'Play however you like',
    body:
      'Connect a MIDI keyboard, or use your computer keyboard — A S D F G H J for the ' +
      'white notes, W E T Y U for the black ones. You can also just click the on-screen ' +
      'piano. All three are scored the same way.',
  },
  {
    icon: '🧩',
    title: 'Work in chunks',
    body:
      'The piece is split into short passages at natural phrase boundaries. Loop each ' +
      'one until you are hitting around 90% accuracy, then move on — the plan will take ' +
      'you back for the joins later.',
  },
  {
    icon: '␣',
    title: 'Space starts and stops',
    body:
      'Press Space to play or stop, R to restart the chunk, L to toggle looping. ' +
      'Follow the cursor on the score — it moves with the audio, not ahead of it.',
  },
];

const STORAGE_KEY = 'pilearn.practiceTour.completed';

/**
 * First-run tour for the practice surface.
 *
 * A toast in the corner rather than a modal spotlight: the whole point is to explain
 * controls the learner can see, and a full-screen overlay would hide them. Nothing is
 * blocked while it is open — you can play, press Space, and the tour just sits there.
 *
 * Completion is stored in localStorage so returning users are not interrupted, and the
 * header keeps a Help button to bring it back.
 */
@Component({
  selector: 'app-practice-tour',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <aside
      class="tour"
      role="dialog"
      aria-labelledby="tour-title"
      aria-describedby="tour-body"
    >
      <div class="tour__head">
        <span class="tour__icon" aria-hidden="true">{{ step().icon }}</span>
        <div class="tour__progress" aria-hidden="true">
          @for (dot of dots(); track $index) {
            <span class="dot" [class.dot--on]="$index <= index()"></span>
          }
        </div>
        <button
          type="button"
          class="tour__close"
          (click)="finish()"
          aria-label="Skip the tour"
          title="Skip"
        >×</button>
      </div>

      <h2 id="tour-title" class="tour__title">{{ step().title }}</h2>
      <p id="tour-body" class="tour__body">{{ step().body }}</p>

      <div class="tour__actions">
        <span class="tour__count">{{ index() + 1 }} of {{ total }}</span>
        <div class="tour__buttons">
          @if (index() > 0) {
            <button type="button" class="btn btn--ghost" (click)="back()">Back</button>
          }
          @if (isLast()) {
            <button type="button" class="btn btn--primary" (click)="finish()">
              Start practising
            </button>
          } @else {
            <button type="button" class="btn btn--primary" (click)="next()">Next</button>
          }
        </div>
      </div>
    </aside>
  `,
  styles: [
    `
      :host {
        position: fixed;
        right: clamp(0.75rem, 2vw, 1.5rem);
        bottom: clamp(4.5rem, 9vh, 6rem);
        z-index: 60;
        max-width: min(23rem, calc(100vw - 1.5rem));
      }

      .tour {
        padding: 0.95rem 1.05rem;
        border: 1px solid #2c3038;
        border-radius: 14px;
        background: color-mix(in srgb, #16181c 94%, transparent);
        backdrop-filter: blur(12px);
        color: #e8eaed;
        box-shadow: 0 14px 40px rgb(0 0 0 / 42%);
      }

      .tour__head { display: flex; align-items: center; gap: 0.6rem; margin-bottom: 0.6rem; }
      .tour__icon { font-size: 1.15rem; }
      .tour__progress { display: flex; gap: 0.25rem; flex: 1; }
      .dot { width: 16px; height: 3px; border-radius: 2px; background: #2c3038; }
      .dot--on { background: #6366f1; }

      .tour__close {
        width: 22px; height: 22px; border: 0; border-radius: 5px;
        background: transparent; color: #98a0ac; font-size: 1.05rem;
        line-height: 1; cursor: pointer;
      }
      .tour__close:hover { background: #22262d; color: #e8eaed; }

      .tour__title { margin: 0 0 0.35rem; font-size: 0.95rem; }
      .tour__body { margin: 0 0 0.9rem; font-size: 0.8rem; line-height: 1.55; color: #b6bcc6; }

      .tour__actions { display: flex; align-items: center; justify-content: space-between; gap: 0.6rem; }
      .tour__count { font-size: 0.7rem; color: #7d8590; font-variant-numeric: tabular-nums; }
      .tour__buttons { display: flex; gap: 0.4rem; }

      .btn {
        padding: 0.4rem 0.8rem; border-radius: 7px; border: 1px solid transparent;
        font-size: 0.78rem; font-weight: 600; cursor: pointer;
      }
      .btn--primary { background: #6366f1; color: #fff; }
      .btn--primary:hover { background: #4f46e5; }
      .btn--ghost { background: transparent; border-color: #2c3038; color: #b6bcc6; }
      .btn--ghost:hover { background: #22262d; }

      @media (prefers-reduced-motion: reduce) { .btn { transition: none; } }
    `,
  ],
})
export class PracticeTourComponent {
  private readonly platformId = inject(PLATFORM_ID);

  readonly completed = output<void>();

  readonly index = signal(0);
  readonly total = STEPS.length;

  readonly step = computed(() => STEPS[this.index()]);
  readonly isLast = computed(() => this.index() === STEPS.length - 1);
  readonly dots = computed(() => STEPS.map((_, i) => i));

  /** Whether the tour has already been completed on this device. */
  static hasCompleted(platformId: object): boolean {
    if (!isPlatformBrowser(platformId)) return true; // never show during SSR
    try {
      return localStorage.getItem(STORAGE_KEY) === '1';
    } catch {
      // Private browsing can throw on access; showing the tour is the safer default.
      return false;
    }
  }

  static reset(platformId: object): void {
    if (!isPlatformBrowser(platformId)) return;
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Nothing to do — the tour simply shows again next load.
    }
  }

  next(): void {
    if (!this.isLast()) this.index.update((i) => i + 1);
  }

  back(): void {
    if (this.index() > 0) this.index.update((i) => i - 1);
  }

  finish(): void {
    if (isPlatformBrowser(this.platformId)) {
      try {
        localStorage.setItem(STORAGE_KEY, '1');
      } catch {
        // Storage unavailable; the tour reappears next time, which is acceptable.
      }
    }
    this.completed.emit();
  }

  /** Escape dismisses, matching every other transient panel in the app. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.finish();
  }
}
