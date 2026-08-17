import { HttpClient, HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import {
  Observable,
  type Subscriber,
  catchError,
  concat,
  delay,
  map,
  of,
  switchMap,
  throwError,
  timer,
} from 'rxjs';
import { environment } from '../../../environments/environment';

/** The four stages a learner sees while a score is ingested. */
export type UploadStage = 'UPLOAD' | 'RECOGNISE' | 'ANALYSE' | 'PLAN' | 'DONE';

export interface UploadProgress {
  stage: UploadStage;
  /** 0..1 across the whole job, not within the stage. */
  progress: number;
  message: string;
  /** True when the backend is unreachable and this is a simulated run. */
  simulated: boolean;
  /** Set on DONE — where to send the learner. */
  scoreId?: string;
  /** Pages the OMR could not read. Non-empty means bars are missing. */
  droppedPages?: number[];
}

export class UploadUnavailableError extends Error {
  constructor(public readonly reason: string) {
    super(reason);
    this.name = 'UploadUnavailableError';
  }
}

export class UploadFailedError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly droppedPages: number[] = [],
  ) {
    super(message);
    this.name = 'UploadFailedError';
  }
}

const STAGE_LABELS: Record<UploadStage, string> = {
  UPLOAD: 'Uploading file & inspecting pages…',
  RECOGNISE: 'Running OMR transcription — detecting staves, voices & notes…',
  ANALYSE: 'Segmenting musical phrases & scoring measure difficulty…',
  PLAN: 'Generating your practice roadmap…',
  DONE: 'Ready',
};

/** Fraction complete once each stage finishes. Recognition is the long pole. */
const STAGE_PROGRESS: Record<UploadStage, number> = {
  UPLOAD: 0.15,
  RECOGNISE: 0.6,
  ANALYSE: 0.85,
  PLAN: 0.97,
  DONE: 1,
};

/**
 * Uploads a score and reports ingestion progress.
 *
 * Two paths, and the caller cannot tell them apart except by the `simulated` flag:
 *
 *  - REAL: POST to the API, then poll the job until it reaches a terminal state.
 *  - SIMULATED: when the backend is unreachable, walk the same four stages over ~3s
 *    and hand back the demo score.
 *
 * The simulation exists because the frontend is routinely developed with no backend
 * running, and an upload button that hangs forever is indistinguishable from a broken
 * one. It is deliberately labelled in the UI — a fallback that pretends to be the real
 * thing would be worse than no fallback.
 */
@Injectable({ providedIn: 'root' })
export class ScoreUploadService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = environment.api;

  /** Poll interval while a real job runs. */
  private static readonly POLL_MS = 1200;
  /** Give up on a job that never terminates rather than polling forever. */
  private static readonly MAX_POLLS = 300;

  upload(file: File, title?: string, composer?: string): Observable<UploadProgress> {
    const form = new FormData();
    form.append('file', file);
    form.append('title', title || file.name.replace(/\.[^.]+$/, ''));
    form.append('composer', composer || 'Unknown');

    return this.http
      .post<{ jobId: string; scoreId: string }>(
        `${this.baseUrl}/api/v1/scores/upload`,
        form,
        { reportProgress: true, observe: 'events' },
      )
      .pipe(
        // Upload byte progress is real information — show it rather than a spinner.
        map((event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            const fraction = event.loaded / event.total;
            return {
              kind: 'progress' as const,
              value: {
                stage: 'UPLOAD' as UploadStage,
                progress: fraction * STAGE_PROGRESS.UPLOAD,
                message: STAGE_LABELS.UPLOAD,
                simulated: false,
              },
            };
          }
          if (event.type === HttpEventType.Response && event.body) {
            return { kind: 'accepted' as const, value: event.body };
          }
          return { kind: 'ignore' as const };
        }),
        switchMap((step) => {
          if (step.kind === 'progress') return of(step.value);
          if (step.kind === 'accepted') return this.pollJob(step.value.jobId, step.value.scoreId);
          return of();
        }),
        catchError((error: unknown) => {
          if (this.isUnreachable(error)) {
            return this.simulate();
          }
          return throwError(() => this.toFailure(error));
        }),
      );
  }

  /** Poll the ingestion job until it terminates. */
  private pollJob(jobId: string, scoreId: string): Observable<UploadProgress> {
    return timer(0, ScoreUploadService.POLL_MS).pipe(
      switchMap((tick) => {
        if (tick > ScoreUploadService.MAX_POLLS) {
          return throwError(
            () =>
              new UploadFailedError(
                'Processing is taking longer than expected. The job may still finish — ' +
                  'check the library in a few minutes.',
                'TIMEOUT',
              ),
          );
        }
        return this.http.get<JobStatusResponse>(
          `${this.baseUrl}/api/v1/omr/jobs/${jobId}`,
        );
      }),
      map((status) => this.toProgress(status, scoreId)),
      // Complete the stream once a terminal state is reported.
      takeUntilDone(),
      catchError((error: unknown) => {
        if (error instanceof UploadFailedError) return throwError(() => error);
        if (this.isUnreachable(error)) return this.simulate();
        return throwError(() => this.toFailure(error));
      }),
    );
  }

  private toProgress(status: JobStatusResponse, scoreId: string): UploadProgress {
    const dropped = status.pages?.droppedPages ?? [];

    if (status.status === 'FAILED') {
      throw new UploadFailedError(
        status.errorDetail || 'The score could not be processed.',
        status.errorCode || 'PIPELINE_FAILED',
        dropped,
      );
    }

    if (status.status === 'COMPLETED' || status.status === 'REVIEW_REQUIRED') {
      return {
        stage: 'DONE',
        progress: 1,
        message: STAGE_LABELS.DONE,
        simulated: false,
        scoreId,
        droppedPages: dropped,
      };
    }

    const stage = this.mapStage(status.stage);
    return {
      stage,
      progress: Math.max(status.progress ?? 0, STAGE_PROGRESS.UPLOAD),
      message: status.message || STAGE_LABELS[stage],
      simulated: false,
      droppedPages: dropped,
    };
  }

  /** Worker pipeline stage → the four stages the UI shows. */
  private mapStage(stage: string | undefined): UploadStage {
    switch (stage) {
      case 'INTAKE':
      case 'RASTERISE':
        return 'UPLOAD';
      case 'RECOGNISE':
      case 'MERGE':
      case 'NORMALISE':
        return 'RECOGNISE';
      case 'VALIDATE':
      case 'ENRICH':
      case 'BUILD':
        return 'ANALYSE';
      case 'ANALYSE':
        return 'PLAN';
      default:
        return 'RECOGNISE';
    }
  }

  /**
   * Walk the four stages without a backend.
   *
   * Timings roughly mirror a real single-page run so the stepper does not feel fake,
   * and every emission carries `simulated: true` so the UI must acknowledge it.
   */
  private simulate(): Observable<UploadProgress> {
    const step = (stage: UploadStage, ms: number): Observable<UploadProgress> =>
      of<UploadProgress>({
        stage,
        progress: STAGE_PROGRESS[stage],
        message: STAGE_LABELS[stage],
        simulated: true,
      }).pipe(delay(ms));

    return concat(
      step('UPLOAD', 400),
      step('RECOGNISE', 1200),
      step('ANALYSE', 800),
      step('PLAN', 500),
      of<UploadProgress>({
        stage: 'DONE',
        progress: 1,
        message: STAGE_LABELS.DONE,
        simulated: true,
        scoreId: 'demo',
      }).pipe(delay(300)),
    );
  }

  /**
   * Is this "the server is not there", as opposed to "the server said no"?
   *
   * Status 0 is a network-level failure. 404 means the endpoint does not exist yet,
   * which during this migration means the backend predates the upload route — both are
   * cases where simulating is more useful than an error.
   */
  private isUnreachable(error: unknown): boolean {
    return (
      error instanceof HttpErrorResponse &&
      (error.status === 0 || error.status === 404 || error.status === 502 || error.status === 503)
    );
  }

  private toFailure(error: unknown): UploadFailedError {
    if (error instanceof UploadFailedError) return error;

    if (error instanceof HttpErrorResponse) {
      const detail = (error.error as { detail?: { code?: string; message?: string } })?.detail;
      return new UploadFailedError(
        detail?.message || `The server rejected the upload (${error.status}).`,
        detail?.code || `HTTP_${error.status}`,
      );
    }

    return new UploadFailedError(
      error instanceof Error ? error.message : 'Something went wrong during upload.',
      'UNKNOWN',
    );
  }
}

interface JobStatusResponse {
  status: string;
  stage?: string;
  progress?: number;
  message?: string;
  errorCode?: string;
  errorDetail?: string;
  pages?: { droppedPages?: number[] };
}

/**
 * Complete the stream after the first DONE emission.
 *
 * Written by hand rather than `takeWhile(..., true)` so the terminal value is emitted
 * AND the polling timer is torn down — `takeWhile` alone would keep polling a finished
 * job forever.
 */
function takeUntilDone() {
  return (source: Observable<UploadProgress>): Observable<UploadProgress> =>
    new Observable<UploadProgress>((subscriber: Subscriber<UploadProgress>) => {
      const subscription = source.subscribe({
        next: (value) => {
          subscriber.next(value);
          if (value.stage === 'DONE') subscriber.complete();
        },
        error: (error) => subscriber.error(error),
        complete: () => subscriber.complete(),
      });
      return () => subscription.unsubscribe();
    });
}
