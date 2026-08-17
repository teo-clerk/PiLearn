import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { StagePhase } from '../../services/practice-session.service';

interface PhaseGuide {
  title: string;
  body: string;
  /** The concrete thing that clears this stage — always the real criterion, never a slogan. */
  goal: string;
}

/**
 * What each phase is for, in the learner's words.
 *
 * The wording is deliberately specific about the *mechanism* ("the app plays the other
 * hand", "speeds up when you play clean runs") rather than encouraging. A learner who
 * understands why a stage exists will finish it; one who is only told it is important
 * will skip it.
 */
const GUIDES: Readonly<Record<StagePhase, PhaseGuide>> = {
  HANDS_SEPARATE: {
    title: 'Hands separate',
    body:
      'Focus on one hand while the app plays the other. Get the fingering and shape ' +
      'secure at a relaxed tempo — speed comes later and comes easily once this is solid.',
    goal: 'Two clean runs with this hand alone.',
  },
  CHUNK_DRILL: {
    title: 'Chunk drill',
    body:
      'Both hands now, on just these few bars. The app waits for you, so take the time ' +
      'to find each note rather than pushing through mistakes.',
    goal: 'Two accurate runs with both hands.',
  },
  TEMPO_RAMP: {
    title: 'Tempo ramp',
    body:
      'This is where it becomes muscle memory. The tempo steps up each time you play a ' +
      'clean run, and steps back down if a rung is too fast — that is the system working, ' +
      'not you failing.',
    goal: 'Hold accuracy and timing as the tempo climbs.',
  },
  FULL_FLUENCY: {
    title: 'Full fluency',
    body:
      'Play it through at the target tempo, without the guide track. Small errors are ' +
      'fine here — keeping the pulse going matters more than perfection.',
    goal: 'One clean run at full tempo.',
  },
};

/**
 * Contextual explainer for the current practice phase.
 *
 * Dismissible per phase rather than globally: a learner who has internalised
 * hands-separate practice still benefits from the tempo-ramp explanation the first time
 * they reach it.
 */
@Component({
  selector: 'app-stage-guide',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <aside class="guide" role="note" [attr.aria-label]="'About ' + guide().title">
      <div class="guide__body">
        <h2 class="guide__title">{{ guide().title }}</h2>
        <p class="guide__text">{{ guide().body }}</p>
        <p class="guide__goal">
          <span class="guide__goal-label">To move on:</span>
          {{ criterionText() }}
        </p>
      </div>

      <button
        type="button"
        class="guide__dismiss"
        (click)="dismiss.emit(phase())"
        aria-label="Hide this explanation"
        title="Hide — it will reappear for the next stage type"
      >
        ×
      </button>
    </aside>
  `,
  styles: [
    `
      :host { display: block; }

      .guide {
        display: flex; align-items: flex-start; gap: 0.75rem;
        padding: 0.7rem 0.9rem;
        background: #eff4ff;
        border-bottom: 1px solid #bfdbfe;
        color: #1e3a5f;
      }

      .guide__body { flex: 1; min-width: 0; }
      .guide__title { margin: 0 0 0.15rem; font-size: 0.85rem; font-weight: 700; }
      .guide__text { margin: 0 0 0.3rem; font-size: 0.78rem; line-height: 1.45; }
      .guide__goal { margin: 0; font-size: 0.74rem; opacity: 0.9; }
      .guide__goal-label { font-weight: 600; }

      .guide__dismiss {
        flex: none; width: 24px; height: 24px;
        border: 0; border-radius: 5px; background: transparent;
        font-size: 1.1rem; line-height: 1; cursor: pointer; color: inherit; opacity: 0.6;
      }
      .guide__dismiss:hover { opacity: 1; background: #dbeafe; }

      @media (prefers-color-scheme: dark) {
        .guide { background: #16233a; border-bottom-color: #1e3a5f; color: #bfdbfe; }
        .guide__dismiss:hover { background: #1e3a5f; }
      }
    `,
  ],
})
export class StageGuideComponent {
  readonly phase = input.required<StagePhase>();
  /** Clean runs still required, so the goal line reflects actual progress. */
  readonly runsRemaining = input<number>(0);

  readonly dismiss = output<StagePhase>();

  readonly guide = computed(() => GUIDES[this.phase()]);

  /**
   * The goal, adjusted for progress.
   *
   * "Two clean runs" is discouraging when one is already done; showing what remains is
   * both more accurate and more motivating.
   */
  readonly criterionText = computed(() => {
    const remaining = this.runsRemaining();
    if (remaining <= 0) return this.guide().goal;
    if (remaining === 1) return 'One more clean run.';
    return `${remaining} more clean runs.`;
  });
}
