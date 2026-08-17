import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { type Observable, catchError, forkJoin, map, of, shareReplay, tap, throwError } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DemoScoreService } from './demo-score.service';
import type {
  AlignmentIndex,
  Measure,
  Roadmap,
  ScoreDocument,
} from './score-document.model';

export interface PracticeBundle {
  document: ScoreDocument;
  index: AlignmentIndex;
  /** Engraving source for OSMD. */
  musicXml: string;
  /** Null when roadmap generation failed — the score is still playable. */
  roadmap: Roadmap | null;
}

export interface ScoreLoadState {
  loading: boolean;
  error: string | null;
  document: ScoreDocument | null;
  roadmap: Roadmap | null;
}

/**
 * Fetches the canonical ScoreDocument and its roadmap.
 *
 * The document is immutable per (scoreId, revision), so responses are cached in-memory
 * for the session and served with a long browser cache from the API. The alignment
 * index is fetched separately and eagerly: it is small, it is on the hot path for the
 * cursor, and the full document is only needed once the practice surface opens.
 */
@Injectable({ providedIn: 'root' })
export class ScoreDocumentService {
  private readonly http = inject(HttpClient);
  private readonly demo = inject(DemoScoreService);
  private readonly baseUrl = environment.api;

  private readonly documentCache = new Map<string, Observable<ScoreDocument>>();
  private readonly indexCache = new Map<string, Observable<AlignmentIndex>>();
  private readonly musicXmlCache = new Map<string, Observable<string>>();

  private readonly state = signal<ScoreLoadState>({
    loading: false,
    error: null,
    document: null,
    roadmap: null,
  });

  readonly loadState = this.state.asReadonly();
  readonly document = computed(() => this.state().document);
  readonly roadmap = computed(() => this.state().roadmap);
  readonly isLoading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  /** Measures in notation order — the practice surface's primary index. */
  readonly measures = computed<Measure[]>(() => {
    const document = this.state().document;
    if (!document || document.parts.length === 0) return [];
    return document.parts[0].measures;
  });

  /**
   * True when the score has recognition problems serious enough that a roadmap built
   * on it may teach the wrong bars. The UI must surface this, not bury it.
   */
  readonly requiresReview = computed(() => {
    const document = this.state().document;
    const roadmap = this.state().roadmap;
    return (
      roadmap?.requiresReview === true ||
      document?.confidence.status === 'REVIEW_REQUIRED' ||
      (document?.confidence.pages ?? []).some((page) => !page.recognised)
    );
  });

  private cacheKey(scoreId: string, revision?: number): string {
    return `${scoreId}@${revision ?? 'latest'}`;
  }

  getDocument(scoreId: string, revision?: number): Observable<ScoreDocument> {
    const key = this.cacheKey(scoreId, revision);
    const cached = this.documentCache.get(key);
    if (cached) return cached;

    const url = `${this.baseUrl}/api/v1/scores/${scoreId}/document`;
    const request = this.http
      .get<ScoreDocument>(url, {
        params: revision === undefined ? {} : { revision: String(revision) },
      })
      .pipe(
        tap((document) => this.assertSchemaSupported(document)),
        // The document is immutable, so one fetch serves every subscriber for the
        // lifetime of the session.
        shareReplay({ bufferSize: 1, refCount: false }),
        catchError((error) => this.rethrow(error, `score ${scoreId}`)),
      );

    this.documentCache.set(key, request);
    return request;
  }

  getAlignmentIndex(scoreId: string, revision?: number): Observable<AlignmentIndex> {
    const key = this.cacheKey(scoreId, revision);
    const cached = this.indexCache.get(key);
    if (cached) return cached;

    const url = `${this.baseUrl}/api/v1/scores/${scoreId}/document/index`;
    const request = this.http
      .get<AlignmentIndex>(url, {
        params: revision === undefined ? {} : { revision: String(revision) },
      })
      .pipe(
        shareReplay({ bufferSize: 1, refCount: false }),
        catchError(() =>
          // The index is a projection of the document, so a missing or stale index is
          // recoverable rather than fatal.
          this.getDocument(scoreId, revision).pipe(map((doc) => doc.alignment)),
        ),
      );

    this.indexCache.set(key, request);
    return request;
  }

  /**
   * Fetch the engraving source.
   *
   * The ScoreDocument carries structure and alignment but NOT the MusicXML — that is a
   * separate derived artefact, because OSMD needs the raw document and shipping it
   * inside the JSON would double the payload for every consumer that only wants the
   * alignment index.
   */
  getMusicXml(scoreId: string, revision?: number): Observable<string> {
    const key = this.cacheKey(scoreId, revision);
    const cached = this.musicXmlCache.get(key);
    if (cached) return cached;

    const request = this.http
      .get(`${this.baseUrl}/api/v1/scores/${scoreId}/musicxml`, {
        responseType: 'text',
        params: revision === undefined ? {} : { revision: String(revision) },
      })
      .pipe(
        shareReplay({ bufferSize: 1, refCount: false }),
        catchError((error) => this.rethrow(error, `score ${scoreId} musicxml`)),
      );

    this.musicXmlCache.set(key, request);
    return request;
  }

  getRoadmap(
    scoreId: string,
    options: { revision?: number; goalTempoPct?: number; handsSeparateFirst?: boolean } = {},
  ): Observable<Roadmap> {
    const params: Record<string, string> = {};
    if (options.revision !== undefined) params['revision'] = String(options.revision);
    if (options.goalTempoPct !== undefined) {
      params['goalTempoPct'] = String(options.goalTempoPct);
    }
    if (options.handsSeparateFirst !== undefined) {
      params['handsSeparateFirst'] = String(options.handsSeparateFirst);
    }

    return this.http
      .get<Roadmap>(`${this.baseUrl}/api/v1/scores/${scoreId}/roadmap`, { params })
      .pipe(catchError((error) => this.rethrow(error, `roadmap for ${scoreId}`)));
  }

  /**
   * Load everything the practice surface needs.
   *
   * The document is required; the roadmap is not. A score can be perfectly playable
   * while its roadmap generation fails, and refusing to open the score in that case
   * would turn a degraded feature into an outage. So the roadmap error is swallowed
   * into `null` and surfaced through state, while a document error propagates.
   */
  loadForPractice(
    scoreId: string,
    revision?: number,
  ): Observable<PracticeBundle> {
    this.state.update((current) => ({ ...current, loading: true, error: null }));

    // `/practice/demo` serves a prebuilt score from assets so the surface can be
    // exercised with no backend, database or OMR pipeline running.
    if (DemoScoreService.isDemo(scoreId)) {
      return this.demo.load(scoreId).pipe(
        tap(({ document, roadmap }) => {
          this.state.set({ loading: false, error: null, document, roadmap });
        }),
      );
    }

    return forkJoin({
      document: this.getDocument(scoreId, revision),
      index: this.getAlignmentIndex(scoreId, revision),
      musicXml: this.getMusicXml(scoreId, revision),
      roadmap: this.getRoadmap(scoreId, { revision }).pipe(
        catchError(() => of(null)),
      ),
    }).pipe(
      tap(({ document, roadmap }) => {
        this.state.set({ loading: false, error: null, document, roadmap });
      }),
      catchError((error) => {
        this.state.update((current) => ({
          ...current,
          loading: false,
          error: this.messageFor(error),
        }));
        return throwError(() => error);
      }),
    );
  }

  /** Populate the shared state after a successful load. */
  setLoaded(document: ScoreDocument, roadmap: Roadmap | null): void {
    this.state.set({ loading: false, error: null, document, roadmap });
  }

  clear(): void {
    this.state.set({ loading: false, error: null, document: null, roadmap: null });
  }

  /** Drop cached responses — call after a re-ingestion produces a new revision. */
  invalidate(scoreId: string): void {
    for (const key of [...this.documentCache.keys()]) {
      if (key.startsWith(`${scoreId}@`)) this.documentCache.delete(key);
    }
    for (const key of [...this.indexCache.keys()]) {
      if (key.startsWith(`${scoreId}@`)) this.indexCache.delete(key);
    }
    for (const key of [...this.musicXmlCache.keys()]) {
      if (key.startsWith(`${scoreId}@`)) this.musicXmlCache.delete(key);
    }
  }

  /**
   * Refuse a document whose schema this client was not built against.
   *
   * Silently rendering an unknown schema produces a subtly wrong cursor — the exact
   * failure mode Phase 2 exists to eliminate.
   */
  private assertSchemaSupported(document: ScoreDocument): void {
    if (document.schema_version !== '1.0') {
      throw new Error(
        `unsupported ScoreDocument schema ${document.schema_version}; ` +
          'this client supports 1.0. Reload the app to pick up a newer build.',
      );
    }
  }

  private rethrow(error: unknown, what: string): Observable<never> {
    return throwError(() => new Error(`${this.messageFor(error)} (${what})`));
  }

  private messageFor(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      if (error.status === 404) return 'Score not found or not yet processed';
      if (error.status === 0) return 'Cannot reach the server';
      return `Request failed (${error.status})`;
    }
    return error instanceof Error ? error.message : 'Unexpected error';
  }
}
