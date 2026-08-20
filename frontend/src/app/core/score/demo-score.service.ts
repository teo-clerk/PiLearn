import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { type Observable, forkJoin, map, shareReplay } from 'rxjs';
import type { PracticeBundle } from './score-document.service';
import type { Roadmap, RoadmapChunk, RoadmapStage, ScoreDocument } from './score-document.model';

/** Default demo when the route is just `/practice/demo`. */
export const DEMO_SCORE_ID = 'demo';

/** Every id served from assets rather than the API. */
const DEMO_SLUGS = ['demo', 'bach-prelude-c', 'fur-elise', 'gymnopedie-1'] as const;
export type DemoSlug = (typeof DEMO_SLUGS)[number];

/** A demo piece as advertised on the landing page. */
export interface DemoCatalogEntry {
  slug: DemoSlug;
  title: string;
  composer: string;
  measures: number;
  chunks: number;
  /** 0..8, from the difficulty analyser. */
  grade: number;
  tempoBpm: number;
  keySignature: string;
  estimatedMinutes: number;
}

/**
 * Serves a prebuilt score so the practice surface can be exercised with no backend,
 * no database and no OMR pipeline.
 *
 * The document was produced by the real `document_builder`, not hand-written, so what
 * the browser renders is genuinely representative: 16 bars, four chunks, a phrase
 * boundary at the bar-8 rest, accidentals in the second half so chunk difficulty
 * differs, and a held final chord for testing loop-boundary note-offs.
 *
 * The roadmap is generated here rather than fetched, because `RoadmapService` lives in
 * the Java backend and requiring it would defeat the point of the demo. The ladder
 * mirrors `RoadmapService.buildChunk` — if the two drift, the demo stops being
 * representative, so they are kept deliberately close.
 */
@Injectable({ providedIn: 'root' })
export class DemoScoreService {
  private readonly http = inject(HttpClient);
  private readonly cache = new Map<DemoSlug, Observable<PracticeBundle>>();
  private catalogCache?: Observable<DemoCatalogEntry[]>;

  static isDemo(scoreId: string | null | undefined): boolean {
    return DEMO_SLUGS.includes(scoreId as DemoSlug);
  }

  /** The demo shelf shown on the landing page. */
  catalog(): Observable<DemoCatalogEntry[]> {
    this.catalogCache ??= this.http
      .get<DemoCatalogEntry[]>('assets/demo/catalog.json')
      .pipe(shareReplay({ bufferSize: 1, refCount: false }));
    return this.catalogCache;
  }

  load(slug: string = DEMO_SCORE_ID): Observable<PracticeBundle> {
    const key = (DEMO_SLUGS.includes(slug as DemoSlug) ? slug : DEMO_SCORE_ID) as DemoSlug;

    const existing = this.cache.get(key);
    if (existing) return existing;

    const bundle = forkJoin({
      document: this.http.get<ScoreDocument>(`assets/demo/${key}.document.json`),
      musicXml: this.http.get(`assets/demo/${key}.musicxml`, { responseType: 'text' }),
    }).pipe(
      map(({ document, musicXml }) => ({
        document,
        index: document.alignment,
        musicXml,
        roadmap: this.buildRoadmap(document, key),
      })),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    this.cache.set(key, bundle);
    return bundle;
  }

  /** Mirrors the stage ladder in `RoadmapService` (PRODUCT_SPEC §5.2). */
  private buildRoadmap(document: ScoreDocument, slug: string): Roadmap {
    const targetTempo = document.meta.target_tempo_bpm;
    const chunks: RoadmapChunk[] = document.chunks.map((chunk) => {
      const startPct = Math.min(0.85, Math.max(0.45, 1 - 0.06 * chunk.difficulty));
      const startTempo = this.toGrid(targetTempo * startPct);
      const stages: RoadmapStage[] = [];
      let ordinal = 0;

      const stage = (
        handMode: RoadmapStage['handMode'],
        tempoBpm: number,
        mode: RoadmapStage['mode'],
        accuracy: number,
        rms: number,
        runs: number,
        label = '',
      ): RoadmapStage => ({
        ordinal: ordinal++,
        handMode,
        tempoBpm,
        mode,
        useMetronome: mode !== 'WAIT' && mode !== 'RHYTHM',
        // The demo score stands in for an already-ingested piece, so it mirrors the
        // INTERMEDIATE ladder the backend builds: no beginner aids.
        showNoteNames: false,
        guideOpposingHand: false,
        label: label || `${handMode} · ${tempoBpm} bpm`,
        criterion: {
          minPitchAccuracy: accuracy,
          maxTimingRmsMs: rms,
          consecutiveCleanRuns: runs,
          maxErrorsPerMeasure: 2,
        },
        estimatedMinutes: Math.max(2, Math.ceil((chunk.measure_count * 4 * runs * 2.5) / 60)),
      });

      stages.push(stage('RIGHT', startTempo, 'WAIT', 0.95, 0, 2));
      stages.push(stage('LEFT', startTempo, 'WAIT', 0.95, 0, 2));
      stages.push(stage('BOTH', startTempo, 'WAIT', 0.95, 0, 2));
      stages.push(stage('BOTH', startTempo, 'FLOW', 0.92, 120, 3));

      let rung = startTempo;
      const target = this.toGrid(targetTempo);
      while (rung < target) {
        rung = Math.max(rung + 5, this.toGrid(rung * 1.1));
        const capped = Math.min(rung, target);
        const isFinal = capped >= target;
        stages.push(
          stage('BOTH', capped, 'FLOW', isFinal ? 0.95 : 0.9, isFinal ? 80 : 100, 1),
        );
        if (isFinal) break;
      }

      return {
        ordinal: chunk.ordinal,
        startMeasure: chunk.start_measure,
        endMeasure: chunk.end_measure,
        measureCount: chunk.measure_count,
        difficulty: chunk.difficulty,
        label: chunk.label,
        startTempoBpm: startTempo,
        stages,
      };
    });

    const totalStages = chunks.reduce((sum, c) => sum + c.stages.length, 0);
    const estimatedMinutes = chunks.reduce(
      (sum, c) => sum + c.stages.reduce((s, st) => s + st.estimatedMinutes, 0),
      0,
    );

    return {
      scoreId: slug,
      revision: document.revision,
      title: document.meta.title,
      composer: document.meta.composer,
      measureCount: document.meta.measure_count,
      targetTempoBpm: targetTempo,
      globalGrade: document.difficulty?.global_grade ?? null,
      totalStages,
      estimatedMinutes,
      estimatedWeeks: Math.max(1, Math.ceil(estimatedMinutes / 150)),
      requiresReview: false,
      reviewStatus: 'OK',
      chunks,
    };
  }

  private toGrid(bpm: number): number {
    return Math.round(bpm / 5) * 5;
  }
}
