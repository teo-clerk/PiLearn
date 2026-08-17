import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

/** How the learner is playing. */
export type InputSource = 'MIDI' | 'QWERTY' | 'TOUCH';

interface SourceOption {
  source: InputSource;
  icon: string;
  label: string;
  hint: string;
}

const SOURCES: readonly SourceOption[] = [
  { source: 'MIDI', icon: '🎹', label: 'MIDI keyboard', hint: 'USB or Bluetooth piano' },
  { source: 'QWERTY', icon: '⌨️', label: 'Computer keyboard', hint: 'A S D F… to play' },
  { source: 'TOUCH', icon: '🖱️', label: 'Click / touch', hint: 'Play the on-screen keys' },
];

/**
 * Input mode selector.
 *
 * Practising with no MIDI hardware is the common case, not an edge case — most people
 * meet this app before they own a keyboard. Making the alternatives visible and
 * selectable up front is the difference between "this needs hardware I don't have" and
 * "I can start now".
 *
 * MIDI is shown as unavailable rather than hidden when no device is connected, so the
 * option is discoverable once someone does plug one in.
 */
@Component({
  selector: 'app-input-source-selector',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="selector" role="group" aria-label="Input source">
      @for (option of sources; track option.source) {
        <button
          type="button"
          class="option"
          [class.option--active]="active() === option.source"
          [class.option--unavailable]="!isAvailable(option.source)"
          [attr.aria-pressed]="active() === option.source"
          [disabled]="!isAvailable(option.source)"
          [title]="titleFor(option)"
          (click)="select.emit(option.source)"
        >
          <span class="option__icon" aria-hidden="true">{{ option.icon }}</span>
          <span class="option__text">
            <span class="option__label">{{ option.label }}</span>
            <span class="option__hint">{{ hintFor(option) }}</span>
          </span>
        </button>
      }

      @if (active() === 'QWERTY') {
        <div class="octave" role="group" aria-label="Octave">
          <button
            type="button"
            class="octave__btn"
            (click)="octaveDown.emit()"
            title="Octave down (Z)"
            aria-label="Octave down"
          >−</button>
          <span class="octave__value">C{{ octave() }}</span>
          <button
            type="button"
            class="octave__btn"
            (click)="octaveUp.emit()"
            title="Octave up (X)"
            aria-label="Octave up"
          >+</button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host { display: block; }

      .selector { display: flex; align-items: center; gap: 0.3rem; flex-wrap: wrap; }

      .option {
        display: flex; align-items: center; gap: 0.4rem;
        padding: 0.3rem 0.55rem;
        border: 1px solid var(--pl-border, #e2e5ea);
        border-radius: 7px;
        background: transparent;
        cursor: pointer;
        text-align: left;
        transition: background 120ms;
      }
      .option:hover:not(:disabled) { background: #f1f4f8; }
      .option--active { border-color: #2563eb; background: #eff4ff; }
      .option:disabled { opacity: 0.45; cursor: not-allowed; }

      .option__icon { font-size: 0.95rem; line-height: 1; }
      .option__text { display: flex; flex-direction: column; }
      .option__label { font-size: 0.72rem; font-weight: 600; color: var(--pl-text, #1c2024); }
      .option__hint { font-size: 0.62rem; color: var(--pl-muted, #6b7280); }

      .octave {
        display: inline-flex; align-items: center; gap: 0.2rem;
        margin-left: 0.3rem; padding-left: 0.5rem;
        border-left: 1px solid var(--pl-border, #e2e5ea);
      }
      .octave__btn {
        width: 22px; height: 22px; border-radius: 5px;
        border: 1px solid var(--pl-border, #e2e5ea);
        background: transparent; cursor: pointer;
        font-size: 0.85rem; line-height: 1; color: var(--pl-text, #1c2024);
      }
      .octave__btn:hover { background: #f1f4f8; }
      .octave__value {
        font-size: 0.7rem; font-variant-numeric: tabular-nums;
        min-width: 24px; text-align: center; color: var(--pl-muted, #6b7280);
      }

      @media (max-width: 900px) { .option__hint { display: none; } }

      @media (prefers-reduced-motion: reduce) { .option { transition: none; } }

      @media (prefers-color-scheme: dark) {
        .option:hover:not(:disabled), .octave__btn:hover { background: #1e2127; }
        .option--active { background: #1a2438; }
      }
    `,
  ],
})
export class InputSourceSelectorComponent {
  readonly active = input.required<InputSource>();
  /** False when no MIDI device is connected, or the browser has no WebMIDI. */
  readonly midiAvailable = input<boolean>(false);
  /** False on Firefox and Safari, which do not implement WebMIDI at all. */
  readonly midiSupported = input<boolean>(true);
  readonly octave = input<number>(4);

  readonly select = output<InputSource>();
  readonly octaveUp = output<void>();
  readonly octaveDown = output<void>();

  readonly sources = SOURCES;

  isAvailable(source: InputSource): boolean {
    return source === 'MIDI' ? this.midiAvailable() : true;
  }

  hintFor(option: SourceOption): string {
    if (option.source !== 'MIDI') return option.hint;
    if (!this.midiSupported()) return 'Not supported in this browser';
    return this.midiAvailable() ? 'Connected' : 'No device detected';
  }

  titleFor(option: SourceOption): string {
    if (option.source === 'MIDI' && !this.midiSupported()) {
      return 'WebMIDI is only available in Chromium-based browsers';
    }
    if (option.source === 'MIDI' && !this.midiAvailable()) {
      return 'Connect a MIDI keyboard, then reload';
    }
    return `${option.label} — ${option.hint}`;
  }
}
