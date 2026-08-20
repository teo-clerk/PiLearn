import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  PLATFORM_ID,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { HostListener } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../../account/services/auth.service';
import type { HandMode } from '../../../core/score/score-document.model';
import { ScoreDocumentService } from '../../../core/score/score-document.service';
import { AlignmentCursorService } from '../../services/alignment-cursor.service';
import { PracticeAudioService } from '../../services/practice-audio.service';
import { PracticeQwertyService } from '../../services/practice-qwerty.service';
import { PracticeMidiService } from '../../services/practice-midi.service';
import { PracticeSessionService } from '../../services/practice-session.service';
import { WaitGateService } from '../../services/wait-gate.service';
import { UserProfileService } from '../../../core/profile/user-profile.service';
import { MyScoresService } from '../../../library/services/my-scores.service';
import { wantsNoteNames } from '../../../core/profile/user-profile.model';
import { PracticeHeaderComponent } from '../practice-header/practice-header.component';
import {
  type ChunkRange,
  ScoreViewerComponent,
} from '../score-viewer/score-viewer.component';
import { AttemptSummaryComponent } from '../attempt-summary/attempt-summary.component';
import { GuestBannerComponent } from '../guest-banner/guest-banner.component';
import {
  type InputSource,
  InputSourceSelectorComponent,
} from '../input-source-selector/input-source-selector.component';
import { type HudNote, NoteHudComponent } from '../note-hud/note-hud.component';
import { PracticeTourComponent } from '../practice-tour/practice-tour.component';
import { StageGuideComponent } from '../stage-guide/stage-guide.component';
import { VirtualKeyboardComponent } from '../virtual-keyboard/virtual-keyboard.component';
import { midiToName } from '../virtual-keyboard/keyboard-geometry';

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
    GuestBannerComponent,
    InputSourceSelectorComponent,
    StageGuideComponent,
    NoteHudComponent,
    PracticeTourComponent,
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
  private readonly qwerty = inject(PracticeQwertyService);
  readonly session = inject(PracticeSessionService);
  private readonly waitGate = inject(WaitGateService);
  private readonly profileService = inject(UserProfileService);
  private readonly myScores = inject(MyScoresService);

  /** Bound from the route: `/practice/:scoreId`. */
  readonly scoreId = input.required<string>();
  readonly revision = input<number | undefined>(undefined);

  readonly musicXml = signal<string | null>(null);
  readonly isBootstrapping = signal(true);
  readonly bootstrapError = signal<string | null>(null);
  readonly showSummary = signal(false);

  /**
   * Whether to nudge an anonymous visitor towards an account.
   *
   * Shown only once the score has loaded and only until dismissed: the banner exists to
   * explain what a guest loses (saved progress), not to gate practice on signing up.
   */
  private readonly isSignedIn = toSignal(inject(AuthService).isLoggedIn, {
    initialValue: false,
  });
  private readonly guestBannerDismissed = signal(false);
  readonly showGuestBanner = computed(
    () => !this.isSignedIn() && !this.guestBannerDismissed(),
  );

  dismissGuestBanner(): void {
    this.guestBannerDismissed.set(true);
  }

  /** First-run tour. Suppressed during SSR and for anyone who has already seen it. */
  readonly showTour = signal(!PracticeTourComponent.hasCompleted(inject(PLATFORM_ID)));
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
    const merged = new Set([
      ...this.midi.heldNotes(),
      ...this.qwerty.heldNotes(),
      ...this.virtualNotes(),
    ]);
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

  /**
   * Active input mode.
   *
   * Defaults to TOUCH so a first-time visitor with no hardware can play immediately;
   * it upgrades to MIDI automatically once a device appears.
   */
  readonly inputSource = signal<InputSource>('TOUCH');

  readonly midiSupported = signal(
    typeof navigator !== 'undefined' && 'requestMIDIAccess' in navigator,
  );
  readonly midiAvailable = this.midi.hasDevice;
  readonly qwertyOctave = this.qwerty.octave;

  readonly showKeyHints = computed(() => this.inputSource() === 'QWERTY');
  readonly keyHints = computed(() =>
    this.showKeyHints() ? this.qwerty.keyLabels() : new Map<number, string>(),
  );

  /** Phases the learner has dismissed the explainer for, this session. */
  private readonly dismissedGuides = signal<ReadonlySet<string>>(new Set());
  readonly stagePhase = this.session.stagePhase;
  readonly showStageGuide = computed(
    () => !this.dismissedGuides().has(this.stagePhase()),
  );

  readonly runsRemaining = computed(() => {
    const criterion = this.session.criterion();
    if (!criterion) return 0;
    return Math.max(0, criterion.consecutiveCleanRuns - this.session.consecutivePasses());
  });

  /** Notes the cursor expects next, for the HUD. */
  readonly hudNotes = computed<HudNote[]>(() => {
    const step = this.cursor.currentStep();
    if (!step) return [];

    const ppq = this.document()?.meta.ppq ?? 480;
    const durationBeats = ppq > 0 ? step.duration_ticks / ppq : 1;
    const mode = this.session.handMode();

    return step.pitches
      .filter((_, i) => mode === 'BOTH' || step.hands[i] === mode)
      .map((midi) => ({ midi, durationBeats }));
  });

  readonly lastVerdict = computed(() => this.midi.lastEvent()?.verdict ?? null);
  readonly lastDeviationMs = computed(() => this.midi.lastEvent()?.deviationMs ?? 0);

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

  // ── Beginner aids ──────────────────────────────────────────────────────────

  readonly practiceMode = this.session.practiceMode;

  /** True while the transport is holding for the learner rather than running. */
  readonly isWaitingForLearner = computed(
    () => this.practiceMode() === 'WAIT' && this.isPlaying(),
  );

  /** True on a rhythm stage, where any key counts and pitch is ignored. */
  readonly isRhythmStage = computed(() => this.practiceMode() === 'RHYTHM');

  /** Notes the learner still owes before the cursor moves, in WAIT mode. */
  readonly awaitedNotes = this.waitGate.remaining;

  /**
   * Whether to draw pitch names on the keys.
   *
   * Either the stage asks for them, or the learner told us they cannot read notation.
   * The stage wins on the final run of a beginner ladder, where the labels come off
   * deliberately — that is the point of the stage.
   */
  readonly showNoteNames = computed(() => {
    const stage = this.session.currentStage()?.stage;
    if (stage?.showNoteNames) return true;
    if (stage && !stage.showNoteNames && this.session.roadmap()) return false;
    return wantsNoteNames(this.profileService.profile());
  });

  /**
   * Pitch names for the keys, when the learner needs them.
   *
   * Only the keys the current step expects are named. Labelling all 88 turns the
   * keyboard into a wall of text and buries the one key they are looking for — the
   * labels exist to answer "where is this note", not "what is every note".
   */
  readonly noteLabels = computed<ReadonlyMap<number, string>>(() => {
    if (!this.showNoteNames()) return new Map<number, string>();

    const labels = new Map<number, string>();
    for (const midi of this.expectedNotes()) {
      labels.set(midi, midiToName(midi));
    }
    return labels;
  });

  /** What this stage asks for, in the learner's words. */
  readonly stageLabel = computed(() => this.session.currentStage()?.stage.label ?? '');

  /** A line of guidance for whatever mode is running. */
  readonly modeHint = computed(() => {
    if (this.isRhythmStage()) {
      return 'Tap any key on the beat — the notes come later.';
    }
    if (this.practiceMode() === 'WAIT') {
      const remaining = this.awaitedNotes().length;
      return remaining > 1
        ? 'Play all ' + remaining + ' notes — take as long as you need.'
        : 'Play the highlighted note — take as long as you need.';
    }
    return '';
  });

  constructor() {
    // Load once the route input is available. Bootstrapping in the constructor rather
    // than ngOnInit keeps it out of the SSR path.
    if (isPlatformBrowser(this.platformId)) {
      queueMicrotask(() => this.bootstrap());
    }

    // Prefer real hardware the moment it is available — someone who plugs in a
    // keyboard should not have to find a setting.
    effect(() => {
      if (this.midi.hasDevice() && this.inputSource() === 'TOUCH') {
        this.inputSource.set('MIDI');
      }
    });

    // WAIT mode has no transport, so nothing else would ever score the attempt.
    effect(() => {
      if (this.session.waitChunkComplete() && this.session.isPlaying()) {
        this.onChunkComplete();
      }
    });

    this.destroyRef.onDestroy(() => {
      this.qwerty.disable();
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
    const result = this.session.finishAttempt(this.expectedNoteCountForChunk());
    this.showSummary.set(true);
    this.checkpoint(result.pitchAccuracy);
  }

  /**
   * Tell the backend where the learner got to.
   *
   * Fire-and-forget, and never on the demo score — 'demo' is not a real score id, and
   * posting progress against it would 404 on every attempt.
   *
   * Called on attempt end and stage change rather than continuously: those are the
   * moments the answer actually changes, and a checkpoint per note would be a request
   * per keystroke.
   */
  private checkpoint(masteryScore?: number): void {
    const scoreId = this.scoreId();
    if (!scoreId || scoreId === 'demo') return;

    const stages = this.session.allStages();
    if (stages.length === 0) return;

    this.myScores.recordProgress(scoreId, {
      stageIndex: this.session.currentStage()?.globalIndex ?? 0,
      chunkOrdinal: this.session.currentChunk()?.ordinal ?? 0,
      // What they have passed, not where they are standing — the backend keeps the
      // higher of the two, so a learner revisiting an early bar never loses ground.
      stagesCompleted: this.session.stageCleared()
        ? (this.session.currentStage()?.globalIndex ?? 0) + 1
        : undefined,
      totalStages: stages.length,
      tempoPercent: this.session.tempoPercent(),
      masteryScore,
    });
  }

  restartChunk(): void {
    this.audio.stop();
    const chunk = this.session.currentChunk();
    if (chunk) this.cursor.jumpToMeasure(chunk.startMeasure);
    this.showSummary.set(false);
    this.start();
  }

  selectInputSource(source: InputSource): void {
    this.inputSource.set(source);
    if (source === 'QWERTY') {
      this.qwerty.enable();
    } else {
      this.qwerty.disable();
    }
  }

  shiftOctave(delta: number): void {
    this.qwerty.shiftOctave(delta);
  }

  onTourCompleted(): void {
    this.showTour.set(false);
  }

  /** Re-run the tour from the header's Help button. */
  restartTour(): void {
    PracticeTourComponent.reset(this.platformId);
    this.showTour.set(true);
  }

  dismissStageGuide(phase: string): void {
    this.dismissedGuides.update((set) => new Set([...set, phase]));
  }

  /** A drag that ended off the keyboard — clear every on-screen held note. */
  onVirtualReleaseAll(): void {
    for (const midi of this.virtualNotes()) {
      this.audio.stopLearnerNote(midi);
    }
    this.virtualNotes.set([]);
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

    // QWERTY piano gets first refusal. Its map deliberately excludes R, L and G so the
    // two never contend for the same keystroke, but letting it decide keeps that
    // contract in one place rather than duplicated here.
    if (this.qwerty.handleKeyDown(event)) {
      event.preventDefault();
      return;
    }

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

  @HostListener('document:keyup', ['$event'])
  onKeyup(event: KeyboardEvent): void {
    this.qwerty.handleKeyUp(event);
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
    this.checkpoint();
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
    // Already sounding? A glissando re-enters keys; re-triggering would stutter.
    if (this.virtualNotes().includes(event.midi)) return;

    this.virtualNotes.update((notes) => [...notes, event.midi]);
    this.audio.playLearnerNote(event.midi);

    if (this.session.isPlaying()) {
      // No expected-onset argument: an on-screen click has no meaningful timing to
      // score, so it counts for pitch and is left out of the timing statistics.
      this.session.recordNote(event.midi, performance.now(), null);
    }
  }

  onVirtualKeyUp(event: { midi: number }): void {
    this.virtualNotes.update((notes) => notes.filter((midi) => midi !== event.midi));
    this.audio.stopLearnerNote(event.midi);
  }
}
