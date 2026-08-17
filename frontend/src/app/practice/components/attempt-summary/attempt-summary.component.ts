import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import type { AttemptResult } from '../../services/practice-session.service';

/**
 * Post-attempt summary overlay.
 *
 * Extracted from `PracticeSessionViewComponent`: the modal is a self-contained concern
 * with its own sizeable stylesheet, and keeping it inline pushed the orchestrator's
 * styles past the per-component budget.
 *
 * Purely presentational — it receives a result and emits intent, so it can be rendered
 * in isolation and holds no session state.
 */
@Component({
  selector: 'app-attempt-summary',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './attempt-summary.component.html',
  styleUrl: './attempt-summary.component.css',
})
export class AttemptSummaryComponent {
  readonly result = input.required<AttemptResult>();
  /** True when the stage's clean-run requirement is met. */
  readonly stageCleared = input<boolean>(false);

  readonly retry = output<void>();
  readonly advance = output<void>();
  readonly dismiss = output<void>();

  readonly title = computed(() => {
    switch (this.result().verdict) {
      case 'PASS': return 'Clean run';
      case 'STEP_DOWN': return 'Let us slow down';
      default: return 'Not quite yet';
    }
  });

  /**
   * Advice shown to the learner.
   *
   * Mirrors the adaptation policy in PRODUCT_SPEC §5.3 so the message and the system's
   * actual next move cannot disagree.
   */
  readonly advice = computed(() => {
    const result = this.result();

    if (result.verdict === 'PASS') {
      return this.stageCleared()
        ? 'Stage complete — move on when you are ready.'
        : 'One more clean run to clear this stage.';
    }
    if (result.verdict === 'STEP_DOWN') {
      return 'Drop the tempo a step and rebuild it from there.';
    }

    const worst = this.worstMeasures()[0];
    return worst
      ? `Bar ${worst.measure} is costing the most — try it on its own.`
      : 'Run it again and keep the pulse steady.';
  });

  readonly accuracyPercent = computed(() =>
    Math.round(this.result().pitchAccuracy * 100),
  );

  readonly timingSummary = computed(() => {
    const { rushBiasMs, timingRmsMs } = this.result();
    const direction = rushBiasMs < -20 ? 'ahead of' : rushBiasMs > 20 ? 'behind' : 'on';
    return `${Math.abs(Math.round(rushBiasMs))} ms ${direction} the beat (RMS ${timingRmsMs} ms)`;
  });

  /** The three bars that cost the most, so the advice points somewhere specific. */
  readonly worstMeasures = computed(() =>
    this.result()
      .measureResults.map((m) => ({
        measure: m.measureIndex + 1,
        errors: m.wrong + m.missed,
      }))
      .filter((m) => m.errors > 0)
      .sort((a, b) => b.errors - a.errors)
      .slice(0, 3),
  );
}
