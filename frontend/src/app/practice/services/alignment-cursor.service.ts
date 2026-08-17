import { Injectable, computed, signal } from '@angular/core';
import type { Cursor, OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import type {
  AlignmentIndex,
  Hand,
  TimelineStep,
} from '../../core/score/score-document.model';

/**
 * Maps playback time and MIDI events to OSMD cursor positions.
 *
 * Replaces `desktop/service/cursor.service.ts` (1,101 lines).
 *
 * What the legacy service did on every score load, in the browser:
 *   - walked the OSMD cursor to build a measure->index map
 *   - built an `OsmdArrayElement[]` capped at `maxSecondPassIterations`
 *   - unrolled repeats guarded by `security < 10000` and `MAX_DACAPO`
 *   - ran Smith-Waterman against the MIDI tick stream
 *   - re-hydrated with a 6-step pitch-overlap lookahead
 *   - yielded to the main thread periodically so the tab stayed responsive
 *
 * All of that is deterministic per score, so it now happens once, server-side, at
 * ingestion. This service consumes the result. Every lookup is O(1) against a
 * precomputed map, and the magic iteration caps — which silently produced a wrong
 * cursor on malformed input rather than reporting one — are gone.
 */
@Injectable({ providedIn: 'root' })
export class AlignmentCursorService {
  private readonly index = signal<AlignmentIndex | null>(null);
  private osmd: OpenSheetMusicDisplay | null = null;

  /** Index into `steps`, or -1 before the first step. */
  private readonly stepIndex = signal(-1);

  readonly isReady = computed(() => this.index() !== null);
  readonly stepCount = computed(() => this.index()?.steps.length ?? 0);

  readonly currentStep = computed<TimelineStep | null>(() => {
    const alignment = this.index();
    const position = this.stepIndex();
    if (!alignment || position < 0 || position >= alignment.steps.length) return null;
    return alignment.steps[position];
  });

  readonly currentMeasure = computed(() => this.currentStep()?.measure_index ?? -1);

  /** Pitches expected at the current step — what the assessment engine scores against. */
  readonly expectedPitches = computed<readonly number[]>(
    () => this.currentStep()?.pitches ?? [],
  );

  readonly progress = computed(() => {
    const total = this.stepCount();
    if (total === 0) return 0;
    return Math.min(1, Math.max(0, (this.stepIndex() + 1) / total));
  });

  /**
   * Confidence of the current step's alignment. Surfaced so the UI can warn rather
   * than let a learner practise against a cursor that is quietly wrong.
   */
  readonly currentConfidence = computed(
    () => this.currentStep()?.alignment_confidence ?? 1,
  );

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  attach(osmd: OpenSheetMusicDisplay, index: AlignmentIndex): void {
    this.osmd = osmd;
    this.index.set(index);
    this.stepIndex.set(-1);
  }

  detach(): void {
    this.osmd = null;
    this.index.set(null);
    this.stepIndex.set(-1);
  }

  // ── Lookups (all O(1)) ─────────────────────────────────────────────────────

  /** Resolve a MIDI tick to a step index, or -1 when the tick is not an onset. */
  stepIndexForTick(tick: number): number {
    const alignment = this.index();
    if (!alignment) return -1;
    const resolved = alignment.by_tick[tick];
    return resolved === undefined ? -1 : resolved;
  }

  /** First step of a measure, or -1 when the measure has no notes. */
  stepIndexForMeasure(measureIndex: number): number {
    const alignment = this.index();
    if (!alignment) return -1;
    const resolved = alignment.by_measure[measureIndex];
    return resolved === undefined ? -1 : resolved;
  }

  /**
   * The step sounding at a playback time, by binary search on `start_sec`.
   *
   * O(log n) rather than O(1) because playback time is continuous — there is no map
   * key to look up. n is the step count, so this is single-digit comparisons even for
   * a long piece, and it runs once per animation frame at most.
   */
  stepIndexForTime(seconds: number): number {
    const steps = this.index()?.steps;
    if (!steps || steps.length === 0) return -1;
    if (seconds < steps[0].start_sec) return -1;

    let low = 0;
    let high = steps.length - 1;
    let answer = -1;

    while (low <= high) {
      const middle = (low + high) >> 1;
      if (steps[middle].start_sec <= seconds) {
        answer = middle;
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }
    return answer;
  }

  // ── Movement ───────────────────────────────────────────────────────────────

  /** Advance the cursor to whatever step is sounding at `seconds`. */
  syncToTime(seconds: number): void {
    const target = this.stepIndexForTime(seconds);
    if (target !== this.stepIndex()) {
      this.moveTo(target);
    }
  }

  /** Advance to the step at a MIDI tick. No-op when the tick is not an onset. */
  syncToTick(tick: number): void {
    const target = this.stepIndexForTick(tick);
    if (target >= 0 && target !== this.stepIndex()) {
      this.moveTo(target);
    }
  }

  next(): void {
    const target = this.stepIndex() + 1;
    if (target < this.stepCount()) this.moveTo(target);
  }

  previous(): void {
    const target = this.stepIndex() - 1;
    if (target >= -1) this.moveTo(target);
  }

  /** Jump to the start of a measure — used when entering a practice chunk. */
  jumpToMeasure(measureIndex: number): void {
    const target = this.stepIndexForMeasure(measureIndex);
    if (target >= 0) this.moveTo(target);
  }

  reset(): void {
    this.moveTo(-1);
  }

  /**
   * Move the OSMD cursor to a step.
   *
   * OSMD exposes no absolute seek, so we step its iterator. Because
   * `osmd_cursor_index` is dense and monotonic across the performance order, the walk
   * is the difference between two positions — typically one step — not a search.
   */
  private moveTo(target: number): void {
    const alignment = this.index();
    if (!alignment) return;

    const clamped = Math.max(-1, Math.min(target, alignment.steps.length - 1));
    const previous = this.stepIndex();
    this.stepIndex.set(clamped);

    const cursor = this.osmdCursor();
    if (!cursor) return;

    if (clamped < 0) {
      cursor.reset();
      return;
    }

    const targetCursorIndex = alignment.steps[clamped].osmd_cursor_index;
    const currentCursorIndex =
      previous >= 0 ? alignment.steps[previous].osmd_cursor_index : -1;

    let delta = targetCursorIndex - currentCursorIndex;

    // Backwards or a long jump: reset and walk forward. Cheaper and far more reliable
    // than stepping the iterator backwards through repeat boundaries.
    if (delta < 0) {
      cursor.reset();
      delta = targetCursorIndex + 1;
    }

    for (let i = 0; i < delta && !cursor.iterator.EndReached; i++) {
      cursor.next();
    }
  }

  private osmdCursor(): Cursor | null {
    const cursors = this.osmd?.cursors;
    return cursors && cursors.length > 0 ? cursors[0] : null;
  }

  // ── Queries used by the assessment engine ──────────────────────────────────

  /** Expected pitches for one hand at the current step. */
  expectedPitchesForHand(hand: Hand): number[] {
    const step = this.currentStep();
    if (!step) return [];
    return step.pitches.filter((_, position) => step.hands[position] === hand);
  }

  /**
   * Whether `pitch` is expected within `lookahead` steps of the current position.
   *
   * Used to classify an early note: playing the next chord slightly ahead of the beat
   * is a timing error, not a wrong note, and scoring it as the latter is the fastest
   * way to make a learner distrust the feedback.
   */
  isPitchExpectedNearby(pitch: number, lookahead = 2): boolean {
    const alignment = this.index();
    const position = this.stepIndex();
    if (!alignment || position < 0) return false;

    const last = Math.min(position + lookahead, alignment.steps.length - 1);
    for (let i = position; i <= last; i++) {
      if (alignment.steps[i].pitches.includes(pitch)) return true;
    }
    return false;
  }

  /** All steps within a measure range — the note set for a practice chunk. */
  stepsInRange(startMeasure: number, endMeasure: number): TimelineStep[] {
    const steps = this.index()?.steps;
    if (!steps) return [];
    return steps.filter(
      (step) => step.measure_index >= startMeasure && step.measure_index <= endMeasure,
    );
  }
}
