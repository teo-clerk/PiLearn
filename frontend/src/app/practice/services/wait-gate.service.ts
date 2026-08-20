import { Injectable, computed, inject, signal } from '@angular/core';
import type { HandMode } from '../../core/score/score-document.model';
import { AlignmentCursorService } from './alignment-cursor.service';

/** What the learner still owes before the cursor moves on. */
export interface WaitGateState {
  /** Pitches expected at this step that have not been played yet. */
  remaining: readonly number[];
  /** Pitches expected at this step that have been played. */
  satisfied: readonly number[];
  /** True once nothing is outstanding. */
  complete: boolean;
}

/**
 * The gate that makes "Wait-for-Me" mode work.
 *
 * In `WAIT` mode the transport does not move the cursor — this does, and only once the
 * learner has actually played every note the current step expects. There is no timer and
 * no deadline: a complete beginner hunting for D♭ with one finger is not failing, they
 * are learning where D♭ is, and a cursor that walked away from them mid-search would turn
 * that into a failure.
 *
 * Chords are why this is a set rather than a boolean. A three-note chord is satisfied when
 * all three have sounded, in any order, with any gaps between them — pressing them one at
 * a time is exactly what a beginner does, and is correct.
 */
@Injectable({ providedIn: 'root' })
export class WaitGateService {
  private readonly cursor = inject(AlignmentCursorService);

  private readonly satisfiedState = signal<ReadonlySet<number>>(new Set());

  /** The pitches this step wants, already filtered to the stage's hand. */
  private readonly expectedState = signal<readonly number[]>([]);

  readonly state = computed<WaitGateState>(() => {
    const expected = this.expectedState();
    const satisfied = this.satisfiedState();
    const remaining = expected.filter((midi) => !satisfied.has(midi));

    return {
      remaining,
      satisfied: expected.filter((midi) => satisfied.has(midi)),
      // A step that expects nothing is not "complete" — it is empty, and treating it as
      // satisfied would run the cursor forward through every rest in the piece.
      complete: expected.length > 0 && remaining.length === 0,
    };
  });

  readonly remaining = computed(() => this.state().remaining);
  readonly isComplete = computed(() => this.state().complete);

  /** Point the gate at the current step. Call on every cursor move. */
  arm(handMode: HandMode): void {
    this.expectedState.set(this.expectedForHand(handMode));
    this.satisfiedState.set(new Set());
  }

  /**
   * Register a played pitch.
   *
   * @returns true when this note completed the step, so the caller should advance.
   *
   * A wrong note is not an error here and does not reset progress on the chord. It simply
   * does not count — the learner hears it, sees it was not wanted, and tries again. Wiping
   * the two correct notes they already found would be a punishment for exploring.
   */
  register(midi: number): boolean {
    if (!this.expectedState().includes(midi)) {
      return false;
    }
    if (this.satisfiedState().has(midi)) {
      return false;
    }

    this.satisfiedState.update((current) => new Set(current).add(midi));
    return this.isComplete();
  }

  /**
   * Register any keypress, for rhythm-only stages.
   *
   * @returns true — in `RHYTHM` mode pitch is ignored entirely, so any key completes the
   *     step. Training when to play, without also having to know what to play.
   */
  registerAnyKey(): boolean {
    this.satisfiedState.set(new Set(this.expectedState()));
    return true;
  }

  reset(): void {
    this.expectedState.set([]);
    this.satisfiedState.set(new Set());
  }

  private expectedForHand(handMode: HandMode): readonly number[] {
    if (handMode === 'BOTH') {
      return [...this.cursor.expectedPitches()];
    }
    return this.cursor.expectedPitchesForHand(handMode);
  }
}
