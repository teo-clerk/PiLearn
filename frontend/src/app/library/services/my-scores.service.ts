import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { catchError, of, tap } from 'rxjs';
import { GuestSessionService } from '../../core/session/guest-session.service';
import { environment } from '../../../environments/environment';

/** Ingestion state, in the same words the upload screen uses. */
export type LibraryStatus =
  | 'QUEUED'
  | 'PROCESSING'
  | 'READY'
  | 'REVIEW_REQUIRED'
  | 'FAILED';

/** Mirrors `LibraryEntry` on the backend. */
export interface LibraryEntry {
  scoreId: string;
  title: string;
  composer: string;
  status: LibraryStatus;
  difficulty: number | null;
  difficultyLabel: string | null;
  measureCount: number | null;
  /** 0..1 across the whole roadmap. */
  progress: number;
  stagesCompleted: number;
  totalStages: number;
  /** Where "Resume practice" should reopen. */
  stageIndex: number;
  chunkOrdinal: number;
  tempoPercent: number;
  masteryScore: number | null;
  mastered: boolean;
  lastPracticedAt: string | null;
  uploadedAt: string | null;
  stageSummary: string;
}

/**
 * The learner's own scores.
 *
 * Guest-aware: the session id identifies an anonymous visitor's library, and without one
 * the backend correctly returns nothing. An empty library and a failed request are shown
 * differently, because "you have not uploaded anything yet" and "we could not reach the
 * server" call for completely different next actions from the learner.
 */
@Injectable({ providedIn: 'root' })
export class MyScoresService {
  private readonly http = inject(HttpClient);
  private readonly guestSession = inject(GuestSessionService);
  private readonly baseUrl = environment.api;

  private readonly entriesState = signal<readonly LibraryEntry[]>([]);
  private readonly loadingState = signal(false);
  private readonly errorState = signal<string | null>(null);
  private readonly loadedState = signal(false);

  readonly entries = this.entriesState.asReadonly();
  readonly isLoading = this.loadingState.asReadonly();
  readonly error = this.errorState.asReadonly();
  readonly loaded = this.loadedState.asReadonly();

  readonly isEmpty = computed(
    () => this.loadedState() && this.entriesState().length === 0 && !this.errorState(),
  );

  readonly inProgress = computed(() =>
    this.entriesState().filter((entry) => entry.progress > 0 && !entry.mastered),
  );

  readonly mastered = computed(() =>
    this.entriesState().filter((entry) => entry.mastered),
  );

  load(): void {
    this.loadingState.set(true);
    this.errorState.set(null);

    const sessionId = this.guestSession.sessionId();
    const url = sessionId
      ? `${this.baseUrl}/api/v1/scores/library?guestSessionId=${encodeURIComponent(sessionId)}`
      : `${this.baseUrl}/api/v1/scores/library`;

    this.http
      .get<LibraryEntry[]>(url)
      .pipe(
        tap((entries) => {
          this.entriesState.set(entries);
          this.loadedState.set(true);
        }),
        catchError((error: unknown) => {
          this.entriesState.set([]);
          this.loadedState.set(true);
          this.errorState.set(
            error instanceof HttpErrorResponse && error.status === 0
              ? 'Could not reach the server. Your scores are safe — try again in a moment.'
              : 'Your library could not be loaded.',
          );
          return of([] as LibraryEntry[]);
        }),
      )
      .subscribe({ complete: () => this.loadingState.set(false) });
  }

  /** Save where the learner got to. Fire-and-forget: never block practice on it. */
  recordProgress(
    scoreId: string,
    checkpoint: {
      stageIndex?: number;
      chunkOrdinal?: number;
      stagesCompleted?: number;
      totalStages?: number;
      tempoPercent?: number;
      masteryScore?: number;
    },
  ): void {
    this.http
      .post<void>(`${this.baseUrl}/api/v1/scores/${scoreId}/progress`, {
        ...checkpoint,
        guestSessionId: this.guestSession.sessionId(),
      })
      .pipe(
        // A checkpoint that fails to save is not worth interrupting a practice session
        // for. The learner loses their place, not their work, and the next checkpoint
        // will carry the same information.
        catchError(() => of(void 0)),
      )
      .subscribe();
  }
}
