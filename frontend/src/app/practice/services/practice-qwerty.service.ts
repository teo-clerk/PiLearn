import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { AlignmentCursorService } from './alignment-cursor.service';
import { PracticeAudioService } from './practice-audio.service';
import { PracticeSessionService } from './practice-session.service';

/**
 * QWERTY → semitone offset from the octave root.
 *
 * The conventional two-row piano layout: naturals on the home row, accidentals on the
 * row above, positioned where the black keys physically sit. `A S D F G H J K L` gives
 * C D E F G A B C D, and `W E T Y U O P` gives the five sharps.
 *
 * Supersedes the unused `qwertyKeyConfig` in `shared/model/webmidi.ts`, which used
 * `F G H J K L ;` for naturals — workable, but not what anyone who has used an online
 * piano expects.
 */
const KEY_TO_SEMITONE: Readonly<Record<string, number>> = {
  // Naturals — home row
  KeyA: 0,   // C
  KeyS: 2,   // D
  KeyD: 4,   // E
  KeyF: 5,   // F
  KeyG: 7,   // G
  KeyH: 9,   // A
  KeyJ: 11,  // B
  KeyK: 12,  // C (next octave)
  KeyL: 14,  // D
  Semicolon: 16, // E

  // Accidentals — upper row, sitting above the natural they raise
  KeyW: 1,   // C#
  KeyE: 3,   // D#
  KeyT: 6,   // F#
  KeyY: 8,   // G#
  KeyU: 10,  // A#
  KeyO: 13,  // C#
  KeyP: 15,  // D#
};

/** Octave shift. Both pairs are supported: they sit under different hands. */
const OCTAVE_DOWN = new Set(['KeyZ', 'BracketLeft']);
const OCTAVE_UP = new Set(['KeyX', 'BracketRight']);

/** C2..C6 as octave roots — beyond this the mapping runs off the keyboard. */
const MIN_OCTAVE = 2;
const MAX_OCTAVE = 6;
const DEFAULT_OCTAVE = 4;

/**
 * Computer-keyboard piano input.
 *
 * Lets a learner practise without MIDI hardware. Events are routed through the same
 * `PracticeSessionService.recordNote()` path as real MIDI, with the same audio-clock
 * timing — so timing is scored identically and a QWERTY attempt is directly comparable
 * to a keyboard one.
 *
 * The transport shortcuts (Space/R/L/G) are deliberately NOT part of this mapping and
 * are handled by the practice view; `KeyR`, `KeyL` and `KeyG` are absent above so the
 * two never fight over a keystroke.
 */
@Injectable({ providedIn: 'root' })
export class PracticeQwertyService {
  private readonly session = inject(PracticeSessionService);
  private readonly cursor = inject(AlignmentCursorService);
  private readonly audio = inject(PracticeAudioService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly enabledState = signal(false);
  private readonly octaveState = signal(DEFAULT_OCTAVE);
  private readonly heldState = signal<readonly number[]>([]);

  readonly isEnabled = this.enabledState.asReadonly();
  readonly octave = this.octaveState.asReadonly();
  readonly heldNotes = this.heldState.asReadonly();

  /** Lowest MIDI note the current octave maps to — `C{octave}`. */
  readonly rootMidi = computed(() => (this.octaveState() + 1) * 12);

  /**
   * MIDI note → the key that plays it, for the on-screen hint labels.
   *
   * Recomputed when the octave shifts so the labels follow the mapping rather than
   * going stale and pointing at the wrong keys.
   */
  readonly keyLabels = computed<ReadonlyMap<number, string>>(() => {
    const root = this.rootMidi();
    const labels = new Map<number, string>();
    for (const [code, semitone] of Object.entries(KEY_TO_SEMITONE)) {
      labels.set(root + semitone, this.displayLabel(code));
    }
    return labels;
  });

  private readonly pressedCodes = new Set<string>();

  constructor() {
    this.destroyRef.onDestroy(() => this.disable());
  }

  enable(): void {
    this.enabledState.set(true);
  }

  disable(): void {
    this.enabledState.set(false);
    this.releaseAll();
  }

  shiftOctave(delta: number): void {
    const next = Math.max(MIN_OCTAVE, Math.min(MAX_OCTAVE, this.octaveState() + delta));
    if (next === this.octaveState()) return;
    // Release first: the held notes belong to the old octave, and leaving them sounding
    // would strand a note-on with no matching note-off.
    this.releaseAll();
    this.octaveState.set(next);
  }

  setOctave(octave: number): void {
    this.shiftOctave(octave - this.octaveState());
  }

  /**
   * Handle a keydown. Returns true when the key was consumed as a piano note.
   *
   * The caller decides what to do with an unconsumed key, so transport shortcuts still
   * work while QWERTY input is active.
   */
  handleKeyDown(event: KeyboardEvent): boolean {
    if (!this.enabledState() || this.shouldIgnore(event)) return false;

    if (OCTAVE_DOWN.has(event.code)) {
      this.shiftOctave(-1);
      return true;
    }
    if (OCTAVE_UP.has(event.code)) {
      this.shiftOctave(1);
      return true;
    }

    const semitone = KEY_TO_SEMITONE[event.code];
    if (semitone === undefined) return false;

    // Held keys emit keydown repeatedly at the OS repeat rate. Without this guard a
    // held note would be recorded dozens of times and destroy the accuracy score.
    if (event.repeat || this.pressedCodes.has(event.code)) return true;

    this.pressedCodes.add(event.code);
    const midi = this.rootMidi() + semitone;
    if (midi < 21 || midi > 108) return true;

    this.heldState.update((notes) => (notes.includes(midi) ? notes : [...notes, midi]));
    this.audio.playLearnerNote(midi);
    this.record(midi);
    return true;
  }

  /** Handle a keyup. Returns true when the key was consumed. */
  handleKeyUp(event: KeyboardEvent): boolean {
    if (!this.enabledState()) return false;

    const semitone = KEY_TO_SEMITONE[event.code];
    if (semitone === undefined) return false;
    if (!this.pressedCodes.delete(event.code)) return false;

    const midi = this.rootMidi() + semitone;
    this.heldState.update((notes) => notes.filter((note) => note !== midi));
    this.audio.stopLearnerNote(midi);
    return true;
  }

  /**
   * Score the note, using the same clock and path as real MIDI input.
   *
   * Deliberately identical to `PracticeMidiService.handle`: a QWERTY attempt must be
   * comparable to a hardware one, which it cannot be if the two measure time
   * differently.
   */
  private record(midi: number): void {
    if (!this.session.isPlaying()) return;

    const nowMs = this.audio.currentSeconds() * 1000;
    const step = this.cursor.currentStep();
    const expectedMs = step ? step.start_sec * 1000 : null;

    this.session.recordNote(midi, nowMs, expectedMs);
  }

  /**
   * Ignore keystrokes aimed at a control.
   *
   * Without this, typing in the tempo field or nudging a slider with the arrow keys
   * would fire piano notes into the attempt.
   */
  private shouldIgnore(event: KeyboardEvent): boolean {
    if (event.metaKey || event.ctrlKey || event.altKey) return true;

    const target = event.target as HTMLElement | null;
    if (!target) return false;

    const tag = target.tagName;
    return (
      tag === 'INPUT' ||
      tag === 'TEXTAREA' ||
      tag === 'SELECT' ||
      target.isContentEditable === true
    );
  }

  private releaseAll(): void {
    for (const midi of this.heldState()) {
      this.audio.stopLearnerNote(midi);
    }
    this.pressedCodes.clear();
    this.heldState.set([]);
  }

  /** `KeyA` → `A`, `Semicolon` → `;`, `BracketLeft` → `[`. */
  private displayLabel(code: string): string {
    if (code.startsWith('Key')) return code.slice(3);
    if (code === 'Semicolon') return ';';
    if (code === 'BracketLeft') return '[';
    if (code === 'BracketRight') return ']';
    return code;
  }
}
