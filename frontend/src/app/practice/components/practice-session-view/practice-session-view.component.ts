import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  PLATFORM_ID,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HostListener } from '@angular/core';
import { Router } from '@angular/router';
import type { HandMode } from '../../../core/score/score-document.model';
import { ScoreDocumentService } from '../../../core/score/score-document.service';
import { AlignmentCursorService } from '../../services/alignment-cursor.service';
import { PracticeAudioService } from '../../services/practice-audio.service';
import { PracticeMidiService } from '../../services/practice-midi.service';
import { PracticeSessionService } from '../../services/practice-session.service';
import { PracticeHeaderComponent } from '../practice-header/practice-header.component';
import {
  type ChunkRange,
  ScoreViewerComponent,
} from '../score-viewer/score-viewer.component';
import { AttemptSummaryComponent } from '../attempt-summary/attempt-summary.component';
import { VirtualKeyboardComponent } from '../virtual-keyboard/virtual-keyboard.component';

/**
 * The practice surface.
 *
 * Replaces `desktop/components/workbench/workbench.component.ts` (1,009 lines), which
 * held routing, storage, MIDI, sliders, fullscreen, a guided tour, playback and
 * telemetry in one class. Here the component composes three children and delegates all
 * state to `PracticeSessionService` — it owns only what is genuinely view-local
 * (whether the summary overlay is showing).
 */
@Component({
  selector: 'app-practice-session-view',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    PracticeHeaderComponent,
    ScoreViewerComponent,
    VirtualKeyboardComponent,
    AttemptSummaryComponent,
  ],
  templateUrl: './practice-session-view.component.html',
  styleUrl: './practice-session-view.component.css',
})
export class PracticeSessionViewComponent {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly scoreService = inject(ScoreDocumentService);
  private readonly cursor = inject(AlignmentCursorService);
  private readonly audio = inject(PracticeAudioService);
  private readonly midi = inject(PracticeMidiService);
  readonly session = inject(PracticeSessionService);

  /** Bound from the route: `/practice/:scoreId`. */
  readonly scoreId = input.required<string>();
  readonly revision = input<number | undefined>(undefined);

  readonly musicXml = signal<string | null>(null);
  readonly isBootstrapping = signal(true);
  readonly bootstrapError = signal<string | null>(null);
  readonly showSummary = signal(false);
  readonly beatPulse = this.audio.beatPulse;

  readonly document = this.scoreService.document;
  readonly requiresReview = this.scoreService.requiresReview;
  readonly alignmentIndex = computed(() => this.document()?.alignment ?? null);

  readonly activeChunk = computed<ChunkRange | null>(() => {
    const chunk = this.session.currentChunk();
    return chunk
      ? { startMeasure: chunk.startMeasure, endMeasure: chunk.endMeasure }
      : null;
  });

  /** Pitches the current step expects, filtered by the stage's hand mode. */
  readonly expectedNotes = computed<number[]>(() => {
    const mode = this.session.handMode();
    if (mode === 'BOTH') return [...this.cursor.expectedPitches()];
    return this.cursor.expectedPitchesForHand(mode === 'RIGHT' ? 'RIGHT' : 'LEFT');
  });

  /** Keys pressed on the on-screen keyboard (mouse/touch/keyboard). */
  private readonly virtualNotes = signal<readonly number[]>([]);

  /**
   * Keys currently held — real MIDI input and on-screen presses merged.
   *
   * Both are legitimate ways to play, and the keyboard should light the same either
   * way rather than privileging hardware.
   */
  readonly activeNotes = computed<readonly number[]>(() => {
    const merged = new Set([...this.midi.heldNotes(), ...this.virtualNotes()]);
    return [...merged];
  });

  /**
   * Recently wrong notes.
   *
   * Kept as a small rolling set rather than every wrong note of the attempt: the
   * keyboard shows what just went wrong, and leaving old mistakes lit turns the whole
   * keyboard red within a bar.
   */
  readonly errorNotes = computed<number[]>(() => {
    const notes = this.session.played();
    const recent = notes.slice(-8);
    return recent.filter((n) => n.verdict === 'WRONG').map((n) => n.midi);
  });

  readonly lastResult = this.session.lastResult;
  readonly isPlaying = this.session.isPlaying;
  readonly stageCleared = this.session.stageCleared;
  readonly loopEnabled = this.session.loopEnabled;
  readonly handMode = this.session.handMode;

  readonly handModes: readonly HandMode[] = ['LEFT', 'BOTH', 'RIGHT'];

  readonly guideTrackEnabled = this.audio.guideTrackEnabled;
  readonly guideVolume = this.audio.guideVolume;
  readonly isCountingIn = this.audio.isCountingIn;

  /**
   * Whether a guide track is meaningful right now.
   *
   * Only hands-separate stages have an opposing hand to play, so the control is
   * disabled rather than hidden in both-hands stages — a control that vanishes is
   * harder to learn than one that greys out.
   */
  readonly guideAvailable = computed(() => this.session.handMode() !== 'BOTH');

  readonly guideVolumePercent = computed(() => Math.round(this.guideVolume() * 100));

  constructor() {
    // Load once the route input is available. Bootstrapping in the constructor rather
    // than ngOnInit keeps it out of the SSR path.
    if (isPlatformBrowser(this.platformId)) {
      queueMicrotask(() => this.bootstrap());
    }

    this.destroyRef.onDestroy(() => {
      this.audio.stop();
      this.midi.stop();
      this.cursor.detach();
      this.scoreService.clear();
    });
  }

  private bootstrap(): void {
    const id = this.scoreId();
    if (!id) {
      this.bootstrapError.set('No score was specified.');
      this.isBootstrapping.set(false);
      return;
    }

    this.scoreService
      .loadForPractice(id, this.revision())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ roadmap, musicXml }) => {
          this.musicXml.set(musicXml);
          // Fetching several MB of soundfont on the first Play would stall it; warm
          // it while the learner is still reading the plan.
          void this.audio.prepare();
          if (roadmap) {
            this.session.loadRoadmap(roadmap);
          } else {
            // The score is playable; only the practice plan is missing.
            this.bootstrapError.set(
              'This score has no practice roadmap yet. You can still play through it.',
            );
          }
          this.isBootstrapping.set(false);
        },
        error: (cause: unknown) => {
          this.bootstrapError.set(
            cause instanceof Error ? cause.message : 'The score could not be loaded.',
          );
          this.isBootstrapping.set(false);
        },
      });
  }

  // ── Transport ──────────────────────────────────────────────────────────────

  start(): void {
    this.showSummary.set(false);
    this.session.startAttempt();
    this.midi.start();
    void this.audio.startChunk(() => this.onChunkComplete());
  }

  /**
   * Stop and score the attempt.
   *
   * `notesExpected` is the note count of the whole chunk for the active hand — a
   * learner who stops halfway must not be scored as if the rest were correct.
   */
  stop(): void {
    if (!this.session.isPlaying()) return;
    this.audio.stop();
    this.midi.stop();
    this.session.finishAttempt(this.expectedNoteCountForChunk());
    this.showSummary.set(true);
  }

  /**
   * The chunk played to its end.
   *
   * Only reached when looping is off — `PracticeAudioService` restarts silently while
   * loop is enabled, because firing the summary on every pass would make looping
   * unusable as a drill.
   */
  private onChunkComplete(): void {
    if (!this.session.isPlaying()) return;
    this.midi.stop();
    this.session.finishAttempt(this.expectedNoteCountForChunk());
    this.showSummary.set(true);
  }

  restartChunk(): void {
    this.audio.stop();
    const chunk = this.session.currentChunk();
    if (chunk) this.cursor.jumpToMeasure(chunk.startMeasure);
    this.showSummary.set(false);
    this.start();
  }

  toggleGuideTrack(): void {
    this.audio.setGuideTrackEnabled(!this.guideTrackEnabled());
  }

  onGuideVolumeInput(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    if (Number.isFinite(value)) this.audio.setGuideVolume(value / 100);
  }

  /**
   * Transport keyboard shortcuts.
   *
   * Ignored while a text field has focus, so typing a search term does not restart
   * the chunk. Space is intercepted to stop the page scrolling under the surface.
   */
  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (this.isBootstrapping() || this.showSummary()) return;

    const target = event.target as HTMLElement | null;
    const tag = target?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;

    switch (event.code) {
      case 'Space':
        event.preventDefault();
        this.isPlaying() ? this.stop() : this.start();
        break;
      case 'KeyR':
        event.preventDefault();
        this.restartChunk();
        break;
      case 'KeyL':
        event.preventDefault();
        this.toggleLoop();
        break;
      case 'KeyG':
        if (this.guideAvailable()) {
          event.preventDefault();
          this.toggleGuideTrack();
        }
        break;
      default:
        break;
    }
  }

  toggleLoop(): void {
    this.session.setLoop(!this.loopEnabled());
  }

  selectHandMode(mode: HandMode): void {
    // Hand mode is a property of the stage, so switching means moving to the stage in
    // this chunk that uses it. Overriding it in place would silently diverge from the
    // criterion the attempt is scored against.
    const chunk = this.session.currentChunk();
    if (!chunk) return;

    const target = this.session
      .allStages()
      .find((s) => s.chunk.ordinal === chunk.ordinal && s.stage.handMode === mode);

    if (target) this.session.goToStage(target.globalIndex);
  }

  private expectedNoteCountForChunk(): number {
    const chunk = this.session.currentChunk();
    if (!chunk) return 1;

    const steps = this.cursor.stepsInRange(chunk.startMeasure, chunk.endMeasure);
    const mode = this.session.handMode();

    let count = 0;
    for (const step of steps) {
      count +=
        mode === 'BOTH'
          ? step.pitches.length
          : step.hands.filter((hand) => hand === mode).length;
    }
    return Math.max(1, count);
  }

  // ── Summary overlay ────────────────────────────────────────────────────────

  dismissSummary(): void {
    this.showSummary.set(false);
  }

  advanceStage(): void {
    this.showSummary.set(false);
    this.session.nextStage();
  }

  retryStage(): void {
    this.showSummary.set(false);
    this.restartChunk();
  }

  backToLibrary(): void {
    void this.router.navigate(['/library']);
  }

  // ── Keyboard interaction ───────────────────────────────────────────────────

  onVirtualKeyDown(event: { midi: number }): void {
    this.virtualNotes.update((notes) =>
      notes.includes(event.midi) ? notes : [...notes, event.midi],
    );
    if (this.session.isPlaying()) {
      // No expected-onset argument: an on-screen click has no meaningful timing to
      // score, so it counts for pitch and is left out of the timing statistics.
      this.session.recordNote(event.midi, performance.now(), null);
    }
  }

  onVirtualKeyUp(event: { midi: number }): void {
    this.virtualNotes.update((notes) => notes.filter((midi) => midi !== event.midi));
  }
}
