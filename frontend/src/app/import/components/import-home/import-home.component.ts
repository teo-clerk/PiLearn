import { isPlatformBrowser } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  PLATFORM_ID,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import {
  type DemoCatalogEntry,
  DemoScoreService,
} from '../../../core/score/demo-score.service';
import {
  ScoreUploadService,
  UploadFailedError,
  type UploadStage,
} from '../../services/score-upload.service';

interface StepDefinition {
  stage: UploadStage;
  icon: string;
  label: string;
}

const STEPS: readonly StepDefinition[] = [
  { stage: 'UPLOAD', icon: '⏳', label: 'Uploading & inspecting pages' },
  { stage: 'RECOGNISE', icon: '🔍', label: 'OMR transcription' },
  { stage: 'ANALYSE', icon: '🧠', label: 'Phrases & difficulty' },
  { stage: 'PLAN', icon: '🚀', label: 'Practice roadmap' },
];

const ACCEPTED = ['.pdf', '.musicxml', '.xml', '.mxl', '.mid', '.midi'];
const MAX_BYTES = 50 * 1024 * 1024;

/**
 * Upload and ingestion screen.
 *
 * This route previously rendered nothing at all: `importRouteList` defined `link` and
 * `import-work/:mbid` but no `''`, so `/import` matched the module, found no child
 * route, and left an empty outlet under the shell — the reported "black screen with
 * just the word Import", which was the breadcrumb.
 */
@Component({
  selector: 'app-import-home',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './import-home.component.html',
  styleUrl: './import-home.component.css',
})
export class ImportHomeComponent {
  private readonly platformId = inject(PLATFORM_ID);
  private readonly router = inject(Router);
  private readonly uploadService = inject(ScoreUploadService);
  private readonly demoService = inject(DemoScoreService);
  private readonly destroyRef = inject(DestroyRef);

  readonly steps = STEPS;

  readonly isDragging = signal(false);
  readonly validationError = signal<string | null>(null);
  readonly fileName = signal<string | null>(null);

  readonly isProcessing = signal(false);
  readonly currentStage = signal<UploadStage>('UPLOAD');
  readonly progress = signal(0);
  readonly statusMessage = signal('');
  readonly isSimulated = signal(false);

  readonly failure = signal<{ message: string; code: string; droppedPages: number[] } | null>(
    null,
  );

  readonly demos = toSignal(
    this.demoService.catalog().pipe(catchError(() => of([] as DemoCatalogEntry[]))),
    { initialValue: [] as DemoCatalogEntry[] },
  );

  readonly progressPercent = computed(() => Math.round(this.progress() * 100));

  /** Shortcuts shown under the dropzone — at most three, to stay a shortcut. */
  readonly quickDemos = computed(() => this.demos().slice(0, 3));

  stepState(stage: UploadStage): 'done' | 'active' | 'pending' {
    const order = STEPS.map((s) => s.stage);
    const currentIndex =
      this.currentStage() === 'DONE' ? order.length : order.indexOf(this.currentStage());
    const index = order.indexOf(stage);

    if (index < currentIndex) return 'done';
    if (index === currentIndex) return 'active';
    return 'pending';
  }

  // ── File intake ────────────────────────────────────────────────────────────

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
    if (file) this.accept(file);
  }

  onFileInput(event: Event): void {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (file) this.accept(file);
  }

  private accept(file: File): void {
    this.validationError.set(null);
    this.failure.set(null);

    const name = file.name.toLowerCase();
    if (!ACCEPTED.some((ext) => name.endsWith(ext))) {
      this.validationError.set(
        `${file.name} is not a supported format. Use PDF, MusicXML or MIDI.`,
      );
      return;
    }
    if (file.size > MAX_BYTES) {
      const mb = Math.round(file.size / (1024 * 1024));
      this.validationError.set(`${file.name} is ${mb} MB. The limit is 50 MB.`);
      return;
    }
    if (file.size === 0) {
      this.validationError.set(`${file.name} is empty.`);
      return;
    }

    this.fileName.set(file.name);
    this.start(file);
  }

  private start(file: File): void {
    if (!isPlatformBrowser(this.platformId)) return;

    this.isProcessing.set(true);
    this.currentStage.set('UPLOAD');
    this.progress.set(0);
    this.statusMessage.set('Uploading…');
    this.isSimulated.set(false);

    this.uploadService
      .upload(file)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (update) => {
          this.currentStage.set(update.stage);
          this.progress.set(update.progress);
          this.statusMessage.set(update.message);
          this.isSimulated.set(update.simulated);

          if (update.stage === 'DONE' && update.scoreId) {
            this.finish(update.scoreId, update.droppedPages ?? []);
          }
        },
        error: (error: unknown) => {
          this.isProcessing.set(false);
          const failure =
            error instanceof UploadFailedError
              ? error
              : new UploadFailedError('Something went wrong during upload.', 'UNKNOWN');
          this.failure.set({
            message: failure.message,
            code: failure.code,
            droppedPages: failure.droppedPages,
          });
        },
      });
  }

  /**
   * Navigate once ingestion finishes.
   *
   * A short pause lets the final step render as complete — jumping away the instant the
   * last event arrives makes the stepper look like it never reached the end.
   */
  private finish(scoreId: string, droppedPages: number[]): void {
    this.progress.set(1);
    this.statusMessage.set(
      droppedPages.length > 0
        ? `Done, but ${droppedPages.length} page(s) could not be read.`
        : 'Done — opening your practice plan.',
    );

    setTimeout(() => {
      void this.router.navigate(['/practice', scoreId]);
    }, 600);
  }

  // ── Actions ────────────────────────────────────────────────────────────────

  practiceDemo(slug = 'demo'): void {
    void this.router.navigate(['/practice', slug]);
  }

  dismissFailure(): void {
    this.failure.set(null);
    this.fileName.set(null);
  }

  reset(): void {
    this.isProcessing.set(false);
    this.failure.set(null);
    this.fileName.set(null);
    this.validationError.set(null);
    this.progress.set(0);
  }
}
