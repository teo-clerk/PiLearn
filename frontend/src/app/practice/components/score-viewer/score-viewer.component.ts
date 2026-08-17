import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  PLATFORM_ID,
  ViewChild,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import type { OpenSheetMusicDisplay } from 'opensheetmusicdisplay';
import type { AlignmentIndex } from '../../../core/score/score-document.model';
import { AlignmentCursorService } from '../../services/alignment-cursor.service';

export interface ChunkRange {
  startMeasure: number;
  endMeasure: number;
}

/**
 * OpenSheetMusicDisplay wrapper with reactive cursor tracking and chunk highlighting.
 *
 * Replaces `desktop/components/osmd/osmd.component.ts`, which detected readiness by
 * polling `setTimeout(100)` up to 20 times and then nudged the cursor with a 1-second
 * `next()/previous()` pair. Here readiness is awaited properly and the cursor is driven
 * by `AlignmentCursorService` against a precomputed index.
 *
 * OSMD is loaded dynamically because it is large and touches `document` at import time,
 * which breaks the SSR build.
 */
@Component({
  selector: 'app-score-viewer',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './score-viewer.component.html',
  styleUrl: './score-viewer.component.css',
})
export class ScoreViewerComponent {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly cursorService = inject(AlignmentCursorService);
  private readonly destroyRef = inject(DestroyRef);

  readonly musicXml = input<string | null>(null);
  readonly alignmentIndex = input<AlignmentIndex | null>(null);
  /** Measures outside this range are dimmed. Null shows the whole score at full opacity. */
  readonly activeChunk = input<ChunkRange | null>(null);
  readonly autoScroll = input<boolean>(true);
  readonly showFingerings = input<boolean>(true);

  readonly ready = output<OpenSheetMusicDisplay>();
  readonly loadError = output<string>();

  @ViewChild('osmdHost', { static: true })
  private osmdHost!: ElementRef<HTMLDivElement>;

  @ViewChild('scrollHost', { static: true })
  private scrollHost!: ElementRef<HTMLDivElement>;

  private osmd: OpenSheetMusicDisplay | null = null;
  private scrollFrame: number | null = null;
  /** Guards against a stale render finishing after a newer one started. */
  private renderToken = 0;

  readonly isLoading = signal(false);
  readonly error = signal<string | null>(null);

  constructor() {
    // Render whenever the score changes.
    effect(() => {
      const xml = this.musicXml();
      if (!isPlatformBrowser(this.platformId) || !xml) return;
      void this.render(xml);
    });

    // Re-attach the cursor service whenever the index changes.
    effect(() => {
      const index = this.alignmentIndex();
      if (this.osmd && index) this.cursorService.attach(this.osmd, index);
    });

    // Follow the cursor. Reading `currentStep` is what registers the dependency, so
    // this runs on every cursor advance without a subscription.
    effect(() => {
      const step = this.cursorService.currentStep();
      if (step && this.autoScroll()) this.scheduleScrollToCursor();
    });

    // Dim everything outside the active chunk.
    effect(() => {
      const chunk = this.activeChunk();
      if (this.osmd) this.applyChunkHighlight(chunk);
    });

    this.destroyRef.onDestroy(() => this.teardown());
  }

  private async render(xml: string): Promise<void> {
    const token = ++this.renderToken;
    this.isLoading.set(true);
    this.error.set(null);

    try {
      // Dynamic import: OSMD touches `document` at module scope and would break SSR.
      const { OpenSheetMusicDisplay: Osmd } = await import('opensheetmusicdisplay');
      if (token !== this.renderToken) return;

      this.teardown();

      const osmd = new Osmd(this.osmdHost.nativeElement, {
        autoResize: true,
        backend: 'svg',
        drawTitle: false,
        drawSubtitle: false,
        drawComposer: false,
        drawFingerings: this.showFingerings(),
        followCursor: false, // we own scrolling
      });

      await osmd.load(xml);
      if (token !== this.renderToken) {
        osmd.clear();
        return;
      }

      osmd.render();
      this.osmd = osmd;

      const cursor = osmd.cursors?.[0];
      if (cursor) {
        cursor.SkipInvisibleNotes = true;
        cursor.show();
        cursor.reset();
      }

      const index = this.alignmentIndex();
      if (index) this.cursorService.attach(osmd, index);

      this.applyChunkHighlight(this.activeChunk());
      this.isLoading.set(false);
      this.ready.emit(osmd);
    } catch (cause) {
      if (token !== this.renderToken) return;
      const message =
        cause instanceof Error ? cause.message : 'the score could not be rendered';
      this.error.set(message);
      this.isLoading.set(false);
      this.loadError.emit(message);
    }
  }

  /**
   * Dim measures outside the active chunk.
   *
   * Works on OSMD's rendered SVG through its GraphicSheet measure list rather than a
   * CSS selector: OSMD emits no per-measure classes, and matching on generated ids
   * would break on any OSMD upgrade.
   */
  private applyChunkHighlight(chunk: ChunkRange | null): void {
    const graphic = this.osmd?.GraphicSheet;
    if (!graphic?.MeasureList) return;

    graphic.MeasureList.forEach((staffMeasures, measureIndex) => {
      const inChunk =
        chunk === null ||
        (measureIndex >= chunk.startMeasure && measureIndex <= chunk.endMeasure);

      for (const measure of staffMeasures) {
        // Measures can legitimately be absent for a staff (multi-rest, cross-staff).
        const element = (measure as unknown as { getSVGGElement?: () => SVGGElement | null })
          ?.getSVGGElement?.();
        if (!element) continue;
        element.style.opacity = inChunk ? '1' : '0.28';
        element.style.transition = 'opacity 160ms ease-out';
      }
    });
  }

  /**
   * Keep the cursor in view.
   *
   * Coalesced into one animation frame: cursor advances can arrive faster than the
   * display refreshes, and scrolling per event causes visible stutter.
   */
  private scheduleScrollToCursor(): void {
    if (this.scrollFrame !== null) return;

    this.scrollFrame = requestAnimationFrame(() => {
      this.scrollFrame = null;
      const container = this.scrollHost?.nativeElement;
      const cursorElement = this.osmd?.cursors?.[0]?.cursorElement;
      if (!container || !cursorElement) return;

      const cursorTop = cursorElement.offsetTop;
      const viewTop = container.scrollTop;
      const viewBottom = viewTop + container.clientHeight;

      // Only scroll when the cursor is actually outside the comfortable band —
      // scrolling on every step makes the score twitch.
      const margin = container.clientHeight * 0.25;
      if (cursorTop < viewTop + margin || cursorTop > viewBottom - margin) {
        container.scrollTo({
          top: Math.max(0, cursorTop - container.clientHeight / 3),
          behavior: 'smooth',
        });
      }
    });
  }

  private teardown(): void {
    if (this.scrollFrame !== null) {
      cancelAnimationFrame(this.scrollFrame);
      this.scrollFrame = null;
    }
    if (this.osmd) {
      this.cursorService.detach();
      this.osmd.clear();
      this.osmd = null;
    }
    this.osmdHost?.nativeElement?.replaceChildren();
  }
}
