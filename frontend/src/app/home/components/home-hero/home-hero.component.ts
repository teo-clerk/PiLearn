import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface ValuePill {
  icon: string;
  label: string;
}

/**
 * Landing hero: headline, value pills, and the two calls to action.
 *
 * Extracted from `HomeComponent` because it owns real behaviour — the drop target —
 * not just markup, and because inline it pushed the landing page past the
 * per-component CSS budget. `HomeComponent` is now a thin composition.
 */
@Component({
  selector: 'app-home-hero',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './home-hero.component.html',
  styleUrl: './home-hero.component.css',
})
export class HomeHeroComponent {
  readonly pills = input.required<readonly ValuePill[]>();

  /** Emits a file that passed type and size validation. */
  readonly fileAccepted = output<File>();

  readonly isDragging = signal(false);
  readonly error = signal<string | null>(null);

  private static readonly ACCEPTED = ['.pdf', '.musicxml', '.xml', '.mxl', '.mid', '.midi'];
  private static readonly MAX_BYTES = 50 * 1024 * 1024;

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(true);
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(false);
    const file = event.dataTransfer?.files?.[0];
    if (file) this.validate(file);
  }

  onFileInput(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) this.validate(file);
  }

  /**
   * Validate before emitting.
   *
   * Rejecting here rather than after a page transition puts the message next to the
   * thing the visitor just dropped, which is where they are looking.
   */
  private validate(file: File): void {
    this.error.set(null);

    const name = file.name.toLowerCase();
    if (!HomeHeroComponent.ACCEPTED.some((ext) => name.endsWith(ext))) {
      this.error.set('That file type is not supported. Try a PDF, MusicXML or MIDI.');
      return;
    }
    if (file.size > HomeHeroComponent.MAX_BYTES) {
      this.error.set('That file is over 50 MB. Try a smaller PDF.');
      return;
    }

    this.fileAccepted.emit(file);
  }
}
