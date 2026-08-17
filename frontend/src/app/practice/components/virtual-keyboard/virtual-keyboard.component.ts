import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import {
  MIDI_A0,
  MIDI_C8,
  buildKeyboardLayout,
  nameToMidi,
  type KeyLayout,
} from './keyboard-geometry';

/** Visual state of a key, in precedence order (highest wins). */
export type KeyState = 'error' | 'active' | 'expected' | 'idle';

export interface KeyPressEvent {
  midi: number;
  name: string;
}

/**
 * Native SVG piano keyboard.
 *
 * Replaces `@jesperdj/pianokeys`, which was pulled from an unpinned GitHub fork
 * (`github:piano-ml/pianokeys`). That dependency made builds unreproducible and broke
 * `npm install` anywhere git-protocol fetches are disabled — which is most CI sandboxes.
 * The whole API used from it was one constructor plus `fillKey`/`clearKey`.
 *
 * Rendering is SVG rather than canvas because the keyboard is a static shape with a
 * handful of colour changes per second. SVG gets hit-testing, focus and accessibility
 * from the browser; canvas would mean reimplementing all three.
 */
@Component({
  selector: 'app-virtual-keyboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      class="virtual-keyboard"
      [attr.viewBox]="viewBox()"
      [attr.height]="keyHeight()"
      preserveAspectRatio="xMidYMid meet"
      role="group"
      [attr.aria-label]="ariaLabel()"
    >
      @for (key of layout().keys; track key.midi) {
        <rect
          [attr.x]="key.x"
          [attr.y]="0"
          [attr.width]="key.width"
          [attr.height]="key.height"
          [attr.rx]="key.isBlack ? 2 : 3"
          [attr.class]="cssClassFor(key)"
          [attr.fill]="fillFor(key)"
          [attr.tabindex]="interactive() ? 0 : -1"
          role="button"
          [attr.aria-label]="labelFor(key)"
          [attr.aria-pressed]="stateFor(key.midi) === 'active'"
          (pointerdown)="onPointerDown($event, key)"
          (pointerup)="onPointerUp(key)"
          (pointerleave)="onPointerUp(key)"
          (keydown.enter)="onPointerDown($event, key)"
          (keydown.space)="onPointerDown($event, key)"
          (keyup.enter)="onPointerUp(key)"
          (keyup.space)="onPointerUp(key)"
        />
      }
    </svg>
  `,
  styles: [
    `
      :host {
        display: block;
        width: 100%;
        line-height: 0;
      }

      .virtual-keyboard {
        display: block;
        width: 100%;
        touch-action: none;
        user-select: none;
      }

      .key {
        stroke: #33333355;
        stroke-width: 0.5;
        /* Only paint transitions animate: geometry never changes at runtime, and
           animating it would force layout on every MIDI event. */
        transition: fill 60ms ease-out;
      }

      .key--white {
        fill: #fdfdfd;
      }

      .key--black {
        fill: #1b1b1b;
        stroke: #00000088;
      }

      .key--expected.key--white {
        fill: #d5e8ff;
      }
      .key--expected.key--black {
        fill: #2d4a6b;
      }

      .key--active.key--white {
        fill: #ffe600;
      }
      .key--active.key--black {
        fill: #c9a400;
      }

      .key--error.key--white,
      .key--error.key--black {
        fill: #ff3b30;
      }

      .key:focus-visible {
        outline: none;
        stroke: #0066ff;
        stroke-width: 2;
      }

      @media (prefers-reduced-motion: reduce) {
        .key {
          transition: none;
        }
      }
    `,
  ],
})
export class VirtualKeyboardComponent {
  /** Lowest sounding key. Defaults to A0 (a full 88-key piano). */
  readonly lowestMidi = input<number>(MIDI_A0);
  readonly highestMidi = input<number>(MIDI_C8);
  readonly keyHeight = input<number>(120);

  /** Keys currently held down, from WebMIDI input. */
  readonly activeMidiNotes = input<readonly number[]>([]);
  /** Keys the current practice step expects. */
  readonly expectedNotes = input<readonly number[]>([]);
  /** Keys played that should not have been — instant visual feedback. */
  readonly errorNotes = input<readonly number[]>([]);

  /** Whether clicking or keyboard-focusing a key emits events. */
  readonly interactive = input<boolean>(true);

  readonly keyDown = output<KeyPressEvent>();
  readonly keyUp = output<KeyPressEvent>();

  /**
   * Imperative colour overrides, for the legacy `PlayerKeyboardService` facade.
   *
   * The declarative inputs above are the intended API. This exists so the existing
   * velocity-graded highlighting keeps working during the migration without a rewrite
   * of the player services.
   */
  private readonly overrides = signal<ReadonlyMap<number, string>>(new Map());

  private readonly activeSet = computed(() => new Set(this.activeMidiNotes()));
  private readonly expectedSet = computed(() => new Set(this.expectedNotes()));
  private readonly errorSet = computed(() => new Set(this.errorNotes()));

  readonly layout = computed(() =>
    // Fixed white-key width in viewBox units; the SVG scales it to the host width, so
    // the keyboard is responsive without recomputing geometry on resize.
    buildKeyboardLayout(this.lowestMidi(), this.highestMidi(), 24, this.keyHeight()),
  );

  readonly viewBox = computed(() => {
    const { width, height } = this.layout();
    return `0 0 ${width} ${height}`;
  });

  readonly ariaLabel = computed(() => {
    const { whiteKeyCount } = this.layout();
    return `Piano keyboard, ${whiteKeyCount} white keys`;
  });

  stateFor(midi: number): KeyState {
    // Precedence matters: a wrong note must read as wrong even while it is held, and
    // even if it happens to also be expected later in the bar.
    if (this.errorSet().has(midi)) return 'error';
    if (this.activeSet().has(midi)) return 'active';
    if (this.expectedSet().has(midi)) return 'expected';
    return 'idle';
  }

  cssClassFor(key: KeyLayout): string {
    const state = this.stateFor(key.midi);
    const colour = key.isBlack ? 'key--black' : 'key--white';
    return state === 'idle' ? `key ${colour}` : `key ${colour} key--${state}`;
  }

  /** An imperative override wins; otherwise CSS drives the colour. */
  fillFor(key: KeyLayout): string | null {
    return this.overrides().get(key.midi) ?? null;
  }

  labelFor(key: KeyLayout): string {
    const state = this.stateFor(key.midi);
    const suffix =
      state === 'idle' ? '' : `, ${state === 'active' ? 'pressed' : state}`;
    return `${key.name}${suffix}`;
  }

  onPointerDown(event: Event, key: KeyLayout): void {
    if (!this.interactive()) return;
    event.preventDefault();
    this.keyDown.emit({ midi: key.midi, name: key.name });
  }

  onPointerUp(key: KeyLayout): void {
    if (!this.interactive()) return;
    this.keyUp.emit({ midi: key.midi, name: key.name });
  }

  // ── Imperative facade (pianokeys compatibility) ────────────────────────────

  /** Paint a key. `name` is scientific pitch notation, e.g. "C#4". */
  fillKey(name: string, colour = '#ffe600'): void {
    const midi = nameToMidi(name);
    if (midi === null) return;
    this.overrides.update((current) => new Map(current).set(midi, colour));
  }

  /** Clear an imperative override, returning the key to its declarative state. */
  clearKey(name: string): void {
    const midi = nameToMidi(name);
    if (midi === null) return;
    this.overrides.update((current) => {
      if (!current.has(midi)) return current;
      const next = new Map(current);
      next.delete(midi);
      return next;
    });
  }

  clearAllKeys(): void {
    if (this.overrides().size === 0) return;
    this.overrides.set(new Map());
  }
}
