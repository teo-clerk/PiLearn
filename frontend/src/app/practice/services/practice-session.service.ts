import { Injectable, computed, inject, signal } from '@angular/core';
import type {
  Hand,
  HandMode,
  MasteryCriterion,
  PracticeMode,
  ResolvedStage,
  Roadmap,
  RoadmapChunk,
  RoadmapStage,
} from '../../core/score/score-document.model';
import { AlignmentCursorService } from './alignment-cursor.service';

/** How a played note related to what was expected. */
export type NoteVerdict = 'CORRECT' | 'EARLY' | 'LATE' | 'WRONG' | 'MISSED';

/** Timing windows, carried over from the legacy `player-assess.service.ts`. */
export const PERFECT_WINDOW_MS = 200;
export const GOOD_WINDOW_MS = 600;

export interface PlayedNote {
  midi: number;
  atMs: number;
  verdict: NoteVerdict;
  deviationMs: number;
  measureIndex: number;
}

export interface MeasureTally {
  measureIndex: number;
  expected: number;
  correct: number;
  wrong: number;
  missed: number;
  timingRmsMs: number;
}

export interface AttemptResult {
  verdict: 'PASS' | 'RETRY' | 'STEP_DOWN';
  pitchAccuracy: number;
  notesExpected: number;
  notesCorrect: number;
  notesWrong: number;
  notesMissed: number;
  timingMeanMs: number;
  timingRmsMs: number;
  /** Signed: negative means rushing. */
  rushBiasMs: number;
  measureResults: MeasureTally[];
  durationMs: number;
}

/**
 * State for one practice session.
 *
 * Replaces the practice state scattered through `workbench.component.ts` (1,009 lines)
 * and the mutable public fields of `player-state.service.ts`. Everything here is a
 * signal; state is replaced, never mutated in place, so `computed` values stay correct
 * and nothing can write through a shared reference.
 */
@Injectable({ providedIn: 'root' })
export class PracticeSessionService {
  private readonly cursor = inject(AlignmentCursorService);

  // ── Session ────────────────────────────────────────────────────────────────
  private readonly roadmapState = signal<Roadmap | null>(null);
  private readonly stageIndexState = signal(0);
  private readonly isPlayingState = signal(false);
  private readonly tempoOverrideState = signal<number | null>(null);

  // ── Live attempt ───────────────────────────────────────────────────────────
  private readonly playedState = signal<readonly PlayedNote[]>([]);
  private readonly attemptStartMs = signal(0);
  private readonly attemptCountState = signal(0);
  private readonly consecutivePassesState = signal(0);
  private readonly lastResultState = signal<AttemptResult | null>(null);

  readonly roadmap = this.roadmapState.asReadonly();
  readonly isPlaying = this.isPlayingState.asReadonly();
  readonly played = this.playedState.asReadonly();
  readonly attemptCount = this.attemptCountState.asReadonly();
  readonly consecutivePasses = this.consecutivePassesState.asReadonly();
  readonly lastResult = this.lastResultState.asReadonly();

  /** Every stage across every chunk, flattened into practice order. */
  readonly allStages = computed<ResolvedStage[]>(() => {
    const roadmap = this.roadmapState();
    if (!roadmap) return [];

    const resolved: ResolvedStage[] = [];
    let globalIndex = 0;
    for (const chunk of roadmap.chunks) {
      for (const stage of chunk.stages) {
        resolved.push({ chunk, stage, globalIndex: globalIndex++ });
      }
    }
    return resolved;
  });

  readonly currentStage = computed<ResolvedStage | null>(() => {
    const stages = this.allStages();
    const index = this.stageIndexState();
    return index >= 0 && index < stages.length ? stages[index] : null;
  });

  readonly currentChunk = computed<RoadmapChunk | null>(
    () => this.currentStage()?.chunk ?? null,
  );

  readonly handMode = computed<HandMode>(
    () => this.currentStage()?.stage.handMode ?? 'BOTH',
  );

  readonly practiceMode = computed<PracticeMode>(
    () => this.currentStage()?.stage.mode ?? 'FLOW',
  );

  /** A user override wins over the stage default until the stage changes. */
  readonly targetTempoBpm = computed(
    () => this.tempoOverrideState() ?? this.currentStage()?.stage.tempoBpm ?? 60,
  );

  readonly criterion = computed<MasteryCriterion | null>(
    () => this.currentStage()?.stage.criterion ?? null,
  );

  readonly stageProgress = computed(() => {
    const total = this.allStages().length;
    return total === 0 ? 0 : (this.stageIndexState() + 1) / total;
  });

  /** Live accuracy during an attempt, for the HUD. */
  readonly liveAccuracy = computed(() => {
    const notes = this.playedState();
    if (notes.length === 0) return 1;
    const correct = notes.filter((n) => n.verdict === 'CORRECT').length;
    return correct / notes.length;
  });

  /**
   * Signed rolling timing bias in ms. Negative is rushing.
   *
   * Only CORRECT notes contribute: a wrong note's timing says nothing about tempo, and
   * including it would make the meter swing on note errors rather than timing ones.
   */
  readonly rushBiasMs = computed(() => {
    const timed = this.playedState().filter((n) => n.verdict === 'CORRECT');
    if (timed.length === 0) return 0;
    return timed.reduce((sum, n) => sum + n.deviationMs, 0) / timed.length;
  });

  readonly isRushing = computed(() => this.rushBiasMs() < -60);
  readonly isDragging = computed(() => this.rushBiasMs() > 60);

  // ── Session control ────────────────────────────────────────────────────────

  loadRoadmap(roadmap: Roadmap): void {
    this.roadmapState.set(roadmap);
    this.stageIndexState.set(0);
    this.resetAttempt();
    this.attemptCountState.set(0);
    this.consecutivePassesState.set(0);
    this.tempoOverrideState.set(null);
  }

  goToStage(index: number): void {
    const total = this.allStages().length;
    if (index < 0 || index >= total) return;

    this.stageIndexState.set(index);
    this.tempoOverrideState.set(null);
    this.consecutivePassesState.set(0);
    this.attemptCountState.set(0);
    this.resetAttempt();

    const chunk = this.currentChunk();
    if (chunk) this.cursor.jumpToMeasure(chunk.startMeasure);
  }

  nextStage(): void {
    this.goToStage(this.stageIndexState() + 1);
  }

  previousStage(): void {
    this.goToStage(this.stageIndexState() - 1);
  }

  overrideTempo(bpm: number): void {
    this.tempoOverrideState.set(Math.max(20, Math.min(240, Math.round(bpm))));
  }

  clearTempoOverride(): void {
    this.tempoOverrideState.set(null);
  }

  // ── Attempt lifecycle ──────────────────────────────────────────────────────

  startAttempt(nowMs: number = performance.now()): void {
    this.resetAttempt();
    this.attemptStartMs.set(nowMs);
    this.isPlayingState.set(true);
    const chunk = this.currentChunk();
    if (chunk) this.cursor.jumpToMeasure(chunk.startMeasure);
  }

  /**
   * Record a played note and classify it.
   *
   * `expectedAtMs` is when the note was due; the caller derives it from the transport,
   * which owns wall-clock time. Passing it in keeps this service testable without a
   * running audio context.
   */
  recordNote(midi: number, atMs: number, expectedAtMs: number | null): NoteVerdict {
    const expected = this.cursor.expectedPitches();
    const handFiltered = this.filterByHandMode(expected);

    let verdict: NoteVerdict;
    let deviation = 0;

    if (handFiltered.includes(midi)) {
      deviation = expectedAtMs === null ? 0 : atMs - expectedAtMs;
      const magnitude = Math.abs(deviation);
      if (magnitude <= PERFECT_WINDOW_MS) verdict = 'CORRECT';
      else if (magnitude <= GOOD_WINDOW_MS) verdict = deviation < 0 ? 'EARLY' : 'LATE';
      else verdict = deviation < 0 ? 'EARLY' : 'LATE';
    } else if (this.cursor.isPitchExpectedNearby(midi, 2)) {
      // Right note, wrong moment. Scoring this as WRONG is the fastest way to make a
      // learner stop trusting the feedback.
      verdict = 'EARLY';
      deviation = -PERFECT_WINDOW_MS;
    } else {
      verdict = 'WRONG';
    }

    this.playedState.update((notes) => [
      ...notes,
      {
        midi,
        atMs,
        verdict,
        deviationMs: deviation,
        measureIndex: this.cursor.currentMeasure(),
      },
    ]);

    return verdict;
  }

  /** Note the transport expected but nobody played. */
  recordMissed(midi: number, measureIndex: number, atMs: number): void {
    this.playedState.update((notes) => [
      ...notes,
      { midi, atMs, verdict: 'MISSED', deviationMs: 0, measureIndex },
    ]);
  }

  /**
   * Close the attempt, score it, and advance the mastery counters.
   *
   * `notesExpected` comes from the caller because only it knows how much of the chunk
   * was actually reached — a learner who stops halfway must not be scored as if the
   * remaining notes were correct.
   */
  finishAttempt(notesExpected: number, nowMs: number = performance.now()): AttemptResult {
    const notes = this.playedState();
    const criterion = this.criterion();

    const correct = notes.filter((n) => n.verdict === 'CORRECT');
    const wrong = notes.filter((n) => n.verdict === 'WRONG');
    const missed = notes.filter((n) => n.verdict === 'MISSED');

    const denominator = Math.max(1, notesExpected);
    const accuracy = correct.length / denominator;

    const deviations = correct.map((n) => n.deviationMs);
    const mean =
      deviations.length === 0
        ? 0
        : deviations.reduce((sum, value) => sum + value, 0) / deviations.length;
    const rms =
      deviations.length === 0
        ? 0
        : Math.sqrt(
            deviations.reduce((sum, value) => sum + value * value, 0) / deviations.length,
          );

    const measureResults = this.tallyByMeasure(notes);

    let verdict: AttemptResult['verdict'] = 'RETRY';
    if (criterion) {
      const meetsAccuracy = accuracy >= criterion.minPitchAccuracy;
      const meetsTiming =
        criterion.maxTimingRmsMs === 0 || rms <= criterion.maxTimingRmsMs;
      const worstMeasure = Math.max(
        0,
        ...measureResults.map((m) => m.wrong + m.missed),
      );
      const meetsPerMeasure = worstMeasure <= criterion.maxErrorsPerMeasure;

      if (meetsAccuracy && meetsTiming && meetsPerMeasure) {
        verdict = 'PASS';
      } else if (accuracy < criterion.minPitchAccuracy * 0.7) {
        // Far off, not marginal: dropping the tempo helps more than another attempt.
        verdict = 'STEP_DOWN';
      }
    }

    const result: AttemptResult = {
      verdict,
      pitchAccuracy: Number(accuracy.toFixed(4)),
      notesExpected,
      notesCorrect: correct.length,
      notesWrong: wrong.length,
      notesMissed: missed.length,
      timingMeanMs: Math.round(mean),
      timingRmsMs: Math.round(rms),
      rushBiasMs: Math.round(mean),
      measureResults,
      durationMs: Math.round(nowMs - this.attemptStartMs()),
    };

    this.isPlayingState.set(false);
    this.attemptCountState.update((count) => count + 1);
    this.consecutivePassesState.update((passes) =>
      verdict === 'PASS' ? passes + 1 : 0,
    );
    this.lastResultState.set(result);

    return result;
  }

  /** True when the stage's clean-run requirement has been met. */
  readonly stageCleared = computed(() => {
    const criterion = this.criterion();
    if (!criterion) return false;
    return this.consecutivePassesState() >= criterion.consecutiveCleanRuns;
  });

  private resetAttempt(): void {
    this.playedState.set([]);
    this.lastResultState.set(null);
  }

  /**
   * In a hands-separate stage, the other hand's notes are not expected at all — so a
   * learner practising the right hand is never marked wrong for the left hand's silence.
   */
  private filterByHandMode(pitches: readonly number[]): number[] {
    const mode = this.handMode();
    if (mode === 'BOTH') return [...pitches];
    const hand: Hand = mode === 'RIGHT' ? 'RIGHT' : 'LEFT';
    return this.cursor.expectedPitchesForHand(hand);
  }

  private tallyByMeasure(notes: readonly PlayedNote[]): MeasureTally[] {
    const buckets = new Map<number, PlayedNote[]>();
    for (const note of notes) {
      const existing = buckets.get(note.measureIndex);
      if (existing) existing.push(note);
      else buckets.set(note.measureIndex, [note]);
    }

    return [...buckets.entries()]
      .sort(([a], [b]) => a - b)
      .map(([measureIndex, measureNotes]) => {
        const correct = measureNotes.filter((n) => n.verdict === 'CORRECT');
        const deviations = correct.map((n) => n.deviationMs);
        const rms =
          deviations.length === 0
            ? 0
            : Math.sqrt(
                deviations.reduce((sum, value) => sum + value * value, 0) /
                  deviations.length,
              );

        return {
          measureIndex,
          expected: measureNotes.length,
          correct: correct.length,
          wrong: measureNotes.filter((n) => n.verdict === 'WRONG').length,
          missed: measureNotes.filter((n) => n.verdict === 'MISSED').length,
          timingRmsMs: Math.round(rms),
        };
      });
  }
}
