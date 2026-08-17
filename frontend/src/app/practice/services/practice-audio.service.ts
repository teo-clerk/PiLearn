import { Injectable, computed, inject, signal } from '@angular/core';
import * as Tone from 'tone';
import { PlayerAudioService } from '../../desktop/service/player-audio.service';
import { ScoreDocumentService } from '../../core/score/score-document.service';
import { AlignmentCursorService } from './alignment-cursor.service';
import { PracticeSessionService } from './practice-session.service';

/** A scheduled beat, emitted so the header's metronome indicator can flash in time. */
export interface BeatEvent {
  /** Monotonic counter — the header keys its pulse animation off this. */
  index: number;
  isDownbeat: boolean;
  atSeconds: number;
}

/**
 * Transport and metronome for the practice loop.
 *
 * Composes `PlayerAudioService` (Tone.js transport + SpessaSynth soundfont + metronome
 * click + count-in) rather than reimplementing it. That service is one of the genuinely
 * solid pieces of the legacy codebase; what it lacks is any notion of a *chunk*, which
 * is what this adds:
 *
 *   - playback bounded to the active chunk's measure range, not the whole piece
 *   - tempo taken from the active stage rather than the score's printed tempo
 *   - metronome and count-in driven by the measure's own time signature
 *   - looping within the chunk
 *   - an end-of-chunk callback so the attempt can be scored automatically
 */
@Injectable({ providedIn: 'root' })
export class PracticeAudioService {
  private readonly audio = inject(PlayerAudioService);
  private readonly session = inject(PracticeSessionService);
  private readonly cursor = inject(AlignmentCursorService);
  private readonly scoreDocument = inject(ScoreDocumentService);

  private readonly beatState = signal<BeatEvent | null>(null);
  private readonly isReadyState = signal(false);
  private readonly isCountingInState = signal(false);

  readonly beat = this.beatState.asReadonly();
  readonly isReady = this.isReadyState.asReadonly();
  readonly isCountingIn = this.isCountingInState.asReadonly();

  /** Monotonic beat counter for the header pulse. */
  readonly beatPulse = computed(() => this.beatState()?.index ?? 0);

  /**
   * Current transport position in seconds.
   *
   * This is the clock the learner is playing along to, and the only correct reference
   * for timing drift. Deriving it from the last beat instead would quantise every
   * deviation to a beat boundary and make sub-beat drift unmeasurable.
   */
  currentSeconds(): number {
    return this.audio.getCurrentTime();
  }

  private metronomeTimer: ReturnType<typeof setInterval> | null = null;
  private beatCounter = 0;
  private onChunkEnd: (() => void) | null = null;

  /**
   * Load the soundfont.
   *
   * Idempotent and safe to call ahead of the first play; `PlayerAudioService` caches
   * the promise internally, so a second call awaits the first rather than re-fetching
   * several megabytes.
   */
  async prepare(): Promise<void> {
    if (this.isReadyState()) return;
    await this.audio.initSoundFont();
    this.isReadyState.set(true);
  }

  /**
   * Start playing the active chunk.
   *
   * @param onEnd called when the chunk's last note has passed — the caller scores the
   *   attempt from it, or restarts when looping.
   */
  async startChunk(onEnd: () => void): Promise<void> {
    const chunk = this.session.currentChunk();
    if (!chunk) return;

    await this.prepare();
    this.stopInternal();
    this.onChunkEnd = onEnd;

    const bpm = this.session.targetTempoBpm();
    Tone.getTransport().bpm.value = bpm;

    const startStep = this.cursor.stepIndexForMeasure(chunk.startMeasure);
    const steps = this.cursor.stepsInRange(chunk.startMeasure, chunk.endMeasure);
    if (startStep < 0 || steps.length === 0) return;

    // The document's seconds are at the printed tempo. Practising at 60% means every
    // offset stretches by the inverse ratio — computing this once here keeps the
    // scaling out of every scheduling call below.
    const scale = this.tempoScale();
    const chunkStartSec = steps[0].start_sec;
    const lastStep = steps[steps.length - 1];
    const chunkEndSec =
      (lastStep.start_sec - chunkStartSec + this.stepDurationSec(lastStep)) * scale;

    const countInSec = await this.runCountIn(bpm);

    // Drive the cursor from the transport. Scheduling each step individually (rather
    // than polling) keeps the cursor locked to the audio clock, which is what the
    // learner actually hears.
    for (const step of steps) {
      const at = countInSec + (step.start_sec - chunkStartSec) * scale;
      this.audio.schedule(() => this.cursor.syncToTick(step.start_tick), at);
    }

    this.startMetronome(bpm, countInSec);
    this.audio.scheduleEnd(countInSec + chunkEndSec, () => this.handleChunkEnd());

    this.cursor.jumpToMeasure(chunk.startMeasure);
    await this.audio.start();
  }

  pause(): void {
    this.audio.pause();
    this.stopMetronome();
  }

  stop(): void {
    this.stopInternal();
    this.audio.stop();
    this.cursor.reset();
  }

  /** Ratio between practice tempo and the score's printed tempo. */
  private tempoScale(): number {
    const printed = this.session.roadmap()?.targetTempoBpm ?? 0;
    const practice = this.session.targetTempoBpm();
    if (printed <= 0 || practice <= 0) return 1;
    return printed / practice;
  }

  private stepDurationSec(step: { duration_ticks: number }): number {
    const ppq = this.cursorPpq();
    const bpm = this.session.roadmap()?.targetTempoBpm ?? 120;
    if (ppq <= 0 || bpm <= 0) return 0.25;
    return (step.duration_ticks / ppq) * (60 / bpm);
  }

  private cursorPpq(): number {
    // The document is the authority on ticks-per-quarter. The fallback only guards a
    // divide-by-zero before the document has loaded.
    return this.scoreDocument.document()?.meta.ppq || 480;
  }

  /**
   * Play the count-in and return the offset the music should start at.
   *
   * Returns 0 when count-in is disabled, so callers need no special case.
   */
  private async runCountIn(bpm: number): Promise<number> {
    const bars = this.session.countInBars();
    if (bars === 0) return 0;

    const measure = this.activeMeasureSignature();
    this.isCountingInState.set(true);

    const offset = this.audio.startCountIn(
      bars,
      { timeSignature: [measure.numerator, measure.denominator] } as never,
      bpm,
    );

    // `startCountIn` returns the offset in seconds; clear the flag once it elapses so
    // the UI can stop showing "counting in".
    const offsetSec = typeof offset === 'number' ? offset : 0;
    setTimeout(() => this.isCountingInState.set(false), offsetSec * 1000);
    return offsetSec;
  }

  /**
   * Time signature of the chunk's first measure.
   *
   * Read from the ScoreDocument, not assumed: a 6/8 chunk counted in 4/4 gives the
   * learner the wrong count-in and a metronome on the wrong pulse.
   */
  private activeMeasureSignature(): { numerator: number; denominator: number } {
    const fallback = { numerator: 4, denominator: 4 };
    const chunk = this.session.currentChunk();
    if (!chunk) return fallback;

    const measure = this.scoreDocument
      .measures()
      .find((m) => m.index === chunk.startMeasure);

    return measure?.time_signature ?? fallback;
  }

  /**
   * Emit beats for the metronome indicator, and click when enabled.
   *
   * Uses an interval rather than Tone's scheduler because this drives a UI pulse, not
   * audio: a dropped visual beat is cosmetic, whereas adding UI work to the audio
   * callback risks glitching the sound.
   */
  private startMetronome(bpm: number, startOffsetSec: number): void {
    this.stopMetronome();

    const beatMs = 60_000 / Math.max(1, bpm);
    const { numerator } = this.activeMeasureSignature();

    const begin = () => {
      this.metronomeTimer = setInterval(() => {
        const isDownbeat = this.beatCounter % numerator === 0;
        this.beatCounter += 1;

        this.beatState.set({
          index: this.beatCounter,
          isDownbeat,
          atSeconds: this.audio.getCurrentTime(),
        });

        if (this.session.metronomeEnabled()) {
          this.audio.playMetronomeClick(isDownbeat);
        }
      }, beatMs);
    };

    if (startOffsetSec > 0) {
      setTimeout(begin, startOffsetSec * 1000);
    } else {
      begin();
    }
  }

  private stopMetronome(): void {
    if (this.metronomeTimer !== null) {
      clearInterval(this.metronomeTimer);
      this.metronomeTimer = null;
    }
  }

  private handleChunkEnd(): void {
    const callback = this.onChunkEnd;
    this.stopInternal();

    if (this.session.loopEnabled()) {
      // Loop without scoring: the learner asked to keep drilling, and firing the
      // summary on every pass would make looping unusable.
      void this.startChunk(callback ?? (() => undefined));
      return;
    }

    this.audio.stop();
    callback?.();
  }

  private stopInternal(): void {
    this.stopMetronome();
    this.beatCounter = 0;
    this.audio.clearSchedule();
    this.isCountingInState.set(false);
  }
}
