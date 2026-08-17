import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import {
  type CountInBars,
  PracticeSessionService,
  type StagePhase,
} from '../../services/practice-session.service';

interface PhaseTab {
  phase: StagePhase;
  label: string;
  hint: string;
}

/** Fixed order — this is the pedagogical progression, not a preference. */
const PHASE_TABS: readonly PhaseTab[] = [
  { phase: 'HANDS_SEPARATE', label: 'Hands separate', hint: 'Each hand on its own' },
  { phase: 'CHUNK_DRILL', label: 'Chunk drill', hint: 'Both hands, waiting for you' },
  { phase: 'TEMPO_RAMP', label: 'Tempo ramp', hint: 'Speed up in steps' },
  { phase: 'FULL_FLUENCY', label: 'Full fluency', hint: 'At the target tempo' },
];

/**
 * Practice surface header: phase navigation and transport preferences.
 *
 * Presentational apart from its service injection — it reads signals and calls intent
 * methods, holding no state of its own. That is what lets the whole surface stay
 * consistent when a stage advances from anywhere.
 */
@Component({
  selector: 'app-practice-header',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './practice-header.component.html',
  styleUrl: './practice-header.component.css',
})
export class PracticeHeaderComponent {
  private readonly session = inject(PracticeSessionService);

  /** Beat pulse from the transport, so the metronome indicator can flash in time. */
  readonly beatPulse = input<number>(0);

  readonly tabs = PHASE_TABS;
  readonly countInOptions: readonly CountInBars[] = [0, 1, 2];

  readonly roadmap = this.session.roadmap;
  readonly currentStage = this.session.currentStage;
  readonly currentChunk = this.session.currentChunk;
  readonly activePhase = this.session.stagePhase;
  readonly phaseEntryPoints = this.session.phaseEntryPoints;
  readonly tempoPercent = this.session.tempoPercent;
  readonly targetTempoBpm = this.session.targetTempoBpm;
  readonly metronomeEnabled = this.session.metronomeEnabled;
  readonly countInBars = this.session.countInBars;
  readonly stageProgress = this.session.stageProgress;
  readonly isPlaying = this.session.isPlaying;
  readonly consecutivePasses = this.session.consecutivePasses;
  readonly isRushing = this.session.isRushing;
  readonly isDragging = this.session.isDragging;

  readonly accuracyPercent = computed(() => Math.round(this.session.liveAccuracy() * 100));
  readonly progressPercent = computed(() => Math.round(this.stageProgress() * 100));

  /**
   * Accuracy colour band.
   *
   * Thresholds match the mastery criteria the roadmap actually uses (0.95 / 0.92), so
   * "green" means "this run would pass", not an arbitrary aesthetic cutoff.
   */
  readonly accuracyBand = computed<'good' | 'fair' | 'poor'>(() => {
    const accuracy = this.session.liveAccuracy();
    if (accuracy >= 0.95) return 'good';
    if (accuracy >= 0.85) return 'fair';
    return 'poor';
  });

  readonly timingLabel = computed(() => {
    if (this.isRushing()) return 'Rushing';
    if (this.isDragging()) return 'Dragging';
    return 'In time';
  });

  /** Clean runs still needed to clear the stage. */
  readonly runsRemaining = computed(() => {
    const criterion = this.session.criterion();
    if (!criterion) return 0;
    return Math.max(0, criterion.consecutiveCleanRuns - this.consecutivePasses());
  });

  readonly stageLabel = computed(() => {
    const resolved = this.currentStage();
    if (!resolved) return 'No stage selected';
    const hand =
      resolved.stage.handMode === 'BOTH' ? 'Both hands'
        : resolved.stage.handMode === 'RIGHT' ? 'Right hand' : 'Left hand';
    return `${resolved.chunk.label} · ${hand} · ${resolved.stage.tempoBpm} bpm`;
  });

  isPhaseAvailable(phase: StagePhase): boolean {
    return this.phaseEntryPoints()[phase] >= 0;
  }

  selectPhase(phase: StagePhase): void {
    if (this.isPhaseAvailable(phase)) this.session.goToPhase(phase);
  }

  onTempoInput(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    if (Number.isFinite(value)) this.session.setTempoPercent(value);
  }

  toggleMetronome(): void {
    this.session.setMetronome(!this.metronomeEnabled());
  }

  setCountIn(bars: CountInBars): void {
    this.session.setCountInBars(bars);
  }

  countInLabel(bars: CountInBars): string {
    return bars === 0 ? 'Off' : `${bars} bar${bars > 1 ? 's' : ''}`;
  }
}
