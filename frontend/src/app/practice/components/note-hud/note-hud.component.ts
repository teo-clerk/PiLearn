import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { NoteVerdict } from '../../services/practice-session.service';

/** Note names for display. Sharps only — matches how the keys are labelled. */
const PITCH_NAMES = [
  'C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B',
] as const;

/** Plain-language feedback. "Too early" beats "-180ms" for a beginner. */
const VERDICT_LABELS: Readonly<Record<NoteVerdict, string>> = {
  CORRECT: 'Good',
  EARLY: 'Too early',
  LATE: 'Too late',
  WRONG: 'Wrong note',
  MISSED: 'Missed',
};

export interface HudNote {
  midi: number;
  /** Beats, so "half note" can be named rather than shown in seconds. */
  durationBeats: number;
}

/**
 * Floating HUD showing what to play next and how the last note went.
 *
 * Sits beside the cursor rather than in the header: a learner reading the score is
 * looking at the staff, and feedback three hundred pixels away in a toolbar is feedback
 * they will not see.
 */
@Component({
  selector: 'app-note-hud',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="hud" [class.hud--idle]="targetNotes().length === 0">
      <div class="hud__section">
        <span class="hud__label">Next</span>
        @if (targetNotes().length > 0) {
          <span class="hud__notes">
            @for (note of targetNotes(); track note.midi) {
              <span class="note-chip" [class.note-chip--sharp]="isSharp(note.midi)">
                {{ noteName(note.midi) }}
              </span>
            }
          </span>
          @if (durationLabel(); as label) {
            <span class="hud__duration">{{ label }}</span>
          }
        } @else {
          <span class="hud__notes hud__notes--empty">—</span>
        }
      </div>

      @if (lastVerdict(); as verdict) {
        <div
          class="pill"
          [class.pill--good]="verdict === 'CORRECT'"
          [class.pill--timing]="verdict === 'EARLY' || verdict === 'LATE'"
          [class.pill--bad]="verdict === 'WRONG' || verdict === 'MISSED'"
          role="status"
          aria-live="polite"
        >
          {{ verdictLabel(verdict) }}
          @if (showDeviation()) {
            <span class="pill__detail">{{ deviationLabel() }}</span>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host { display: block; pointer-events: none; }

      .hud {
        display: inline-flex; align-items: center; gap: 0.7rem;
        padding: 0.4rem 0.7rem;
        border-radius: 999px;
        background: color-mix(in srgb, #16181c 88%, transparent);
        color: #e8eaed;
        box-shadow: 0 4px 16px rgb(0 0 0 / 22%);
        font-size: 0.78rem;
      }
      .hud--idle { opacity: 0.55; }

      .hud__section { display: flex; align-items: center; gap: 0.4rem; }
      .hud__label {
        font-size: 0.6rem; text-transform: uppercase; letter-spacing: 0.05em; opacity: 0.6;
      }
      .hud__notes { display: inline-flex; gap: 0.2rem; }
      .hud__notes--empty { opacity: 0.5; }
      .hud__duration { font-size: 0.68rem; opacity: 0.65; }

      .note-chip {
        display: inline-block; min-width: 1.9rem; text-align: center;
        padding: 0.1rem 0.3rem; border-radius: 5px;
        background: #2c3038; font-weight: 600; font-variant-numeric: tabular-nums;
      }
      .note-chip--sharp { background: #3a3f4a; }

      .pill {
        padding: 0.15rem 0.5rem; border-radius: 999px;
        font-size: 0.68rem; font-weight: 600; white-space: nowrap;
      }
      .pill--good { background: #16a34a; color: #fff; }
      .pill--timing { background: #d97706; color: #fff; }
      .pill--bad { background: #dc2626; color: #fff; }
      .pill__detail { margin-left: 0.25rem; opacity: 0.85; font-weight: 400; }

      @media (max-width: 640px) {
        .hud__label, .hud__duration { display: none; }
      }
    `,
  ],
})
export class NoteHudComponent {
  readonly targetNotes = input<readonly HudNote[]>([]);
  readonly lastVerdict = input<NoteVerdict | null>(null);
  /** Signed ms from the last note. Negative is early. */
  readonly lastDeviationMs = input<number>(0);

  isSharp(midi: number): boolean {
    return PITCH_NAMES[midi % 12].includes('♯');
  }

  /** MIDI number to scientific pitch notation, e.g. 61 → C♯4. */
  noteName(midi: number): string {
    return `${PITCH_NAMES[midi % 12]}${Math.floor(midi / 12) - 1}`;
  }

  verdictLabel(verdict: NoteVerdict): string {
    return VERDICT_LABELS[verdict];
  }

  /** Deviation is only meaningful for a note that was actually recognised. */
  readonly showDeviation = computed(() => {
    const verdict = this.lastVerdict();
    return verdict === 'EARLY' || verdict === 'LATE';
  });

  readonly deviationLabel = computed(() => {
    const ms = Math.abs(Math.round(this.lastDeviationMs()));
    return ms > 0 ? `${ms} ms` : '';
  });

  /**
   * Name the note value rather than printing seconds.
   *
   * "Half note" is something a learner can act on; "1.04 s" is not. Dotted values are
   * detected by the 1.5× relationship so they are named correctly.
   */
  readonly durationLabel = computed(() => {
    const notes = this.targetNotes();
    if (notes.length === 0) return '';

    const beats = notes[0].durationBeats;
    const names: [number, string][] = [
      [4, 'whole'],
      [3, 'dotted half'],
      [2, 'half'],
      [1.5, 'dotted quarter'],
      [1, 'quarter'],
      [0.75, 'dotted eighth'],
      [0.5, 'eighth'],
      [0.25, 'sixteenth'],
    ];

    for (const [value, name] of names) {
      if (Math.abs(beats - value) < 0.05) return name;
    }
    return '';
  });
}
