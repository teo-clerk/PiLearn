import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { MidiServiceService } from '../../shared/services/midi-service.service';
import { AlignmentCursorService } from './alignment-cursor.service';
import { PracticeAudioService } from './practice-audio.service';
import { type NoteVerdict, PracticeSessionService } from './practice-session.service';

export interface LiveNoteEvent {
  midi: number;
  verdict: NoteVerdict;
  /** Signed ms against the expected onset. Negative is early. */
  deviationMs: number;
}

/**
 * Routes WebMIDI input into the practice assessment loop.
 *
 * Composes the existing `MidiServiceService` (device discovery, enable/disable,
 * note-on/off as a signal) and adds the piece it has no concept of: *when a note was
 * due*. Drift is measured against the current step's scheduled onset, taken from the
 * audio clock rather than `Date.now()` — the transport is what the learner is playing
 * along to, and wall-clock time drifts from it under load.
 */
@Injectable({ providedIn: 'root' })
export class PracticeMidiService {
  private readonly midi = inject(MidiServiceService);
  private readonly session = inject(PracticeSessionService);
  private readonly cursor = inject(AlignmentCursorService);
  private readonly audio = inject(PracticeAudioService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly heldState = signal<readonly number[]>([]);
  private readonly lastEventState = signal<LiveNoteEvent | null>(null);
  private readonly isListeningState = signal(false);

  /** Keys currently held down — bound straight to the virtual keyboard. */
  readonly heldNotes = this.heldState.asReadonly();
  readonly lastEvent = this.lastEventState.asReadonly();
  readonly isListening = this.isListeningState.asReadonly();

  /** True when at least one MIDI input is connected and enabled. */
  readonly hasDevice = computed(() => this.isListeningState());

  /**
   * The onset the current step is scheduled at, in transport seconds.
   *
   * Recomputed per step rather than cached: the step changes on every cursor advance,
   * and a stale value would bias every deviation measurement in the same direction.
   */
  private expectedOnsetSeconds(): number | null {
    const step = this.cursor.currentStep();
    return step ? step.start_sec : null;
  }

  constructor() {
    // `midiEvent` is a signal, so this reacts without a subscription to unwind.
    effect(() => {
      const event = this.midi.midiEvent();
      if (!event) return;
      this.handle(event.note, event.type, event.time);
    });

    this.destroyRef.onDestroy(() => this.stop());
  }

  start(): void {
    this.isListeningState.set(true);
    this.heldState.set([]);
    this.lastEventState.set(null);
  }

  stop(): void {
    this.isListeningState.set(false);
    this.heldState.set([]);
  }

  /**
   * Handle one note-on/note-off.
   *
   * `eventTimeMs` comes from the WebMIDI event where available. It is used only to
   * order events; the deviation itself is measured against the audio clock, because
   * WebMIDI timestamps and the Tone.js transport share no origin.
   */
  private handle(midi: number, type: 'down' | 'up', eventTimeMs: number): void {
    if (type === 'up') {
      this.heldState.update((notes) => notes.filter((note) => note !== midi));
      return;
    }

    this.heldState.update((notes) =>
      notes.includes(midi) ? notes : [...notes, midi],
    );

    if (!this.session.isPlaying()) {
      // Free play outside an attempt: light the key, score nothing.
      return;
    }

    const nowSec = this.audio.isReady() ? this.currentTransportSeconds() : null;
    const expectedSec = this.expectedOnsetSeconds();

    const nowMs = nowSec !== null ? nowSec * 1000 : eventTimeMs;
    const expectedMs = expectedSec !== null && nowSec !== null ? expectedSec * 1000 : null;

    const verdict = this.session.recordNote(midi, nowMs, expectedMs);

    this.lastEventState.set({
      midi,
      verdict,
      deviationMs: expectedMs === null ? 0 : Math.round(nowMs - expectedMs),
    });
  }

  private currentTransportSeconds(): number {
    // Reading through the audio service keeps Tone.js out of this file, so the
    // transport can be swapped without touching input handling.
    return this.audio.currentSeconds();
  }

  // ── Device management (delegated) ─────────────────────────────────────────

  /** Available inputs, for a device picker. */
  inputs(): MIDIInput[] {
    const access = (this.midi as unknown as { midiAccess?: MIDIAccess }).midiAccess;
    return access ? [...access.inputs.values()] : [];
  }

  enableInput(device: MIDIInput): void {
    this.midi.enableInputMidiDevice(device);
    this.isListeningState.set(true);
  }

  disableInput(device: MIDIInput): void {
    this.midi.disableInputMidiDevice(device);
  }

  isInputEnabled(device: MIDIInput): boolean {
    return this.midi.isInputMidiDeviceEnabled(device);
  }
}
