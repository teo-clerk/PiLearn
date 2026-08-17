import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { type Observable, forkJoin, map, shareReplay } from 'rxjs';
import type { PracticeBundle } from './score-document.service';
import type { Roadmap, RoadmapChunk, RoadmapStage, ScoreDocument } from './score-document.model';

/** The route segment that triggers demo mode: `/practice/demo`. */
export const DEMO_SCORE_ID = 'demo';

const DEMO_DOCUMENT_URL = 'assets/demo/demo-document.json';
const DEMO_MUSICXML_URL = 'assets/demo/demo-score.musicxml';

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
  private cached?: Observable<PracticeBundle>;

  static isDemo(scoreId: string | null | undefined): boolean {
    return scoreId === DEMO_SCORE_ID;
  }

  load(): Observable<PracticeBundle> {
    this.cached ??= forkJoin({
      document: this.http.get<ScoreDocument>(DEMO_DOCUMENT_URL),
      musicXml: this.http.get(DEMO_MUSICXML_URL, { responseType: 'text' }),
    }).pipe(
      map(({ document, musicXml }) => ({
        document,
        index: document.alignment,
        musicXml,
        roadmap: this.buildRoadmap(document),
      })),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

    return this.cached;
  }

  /** Mirrors the stage ladder in `RoadmapService` (PRODUCT_SPEC §5.2). */
  private buildRoadmap(document: ScoreDocument): Roadmap {
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
      ): RoadmapStage => ({
        ordinal: ordinal++,
        handMode,
        tempoBpm,
        mode,
        useMetronome: mode !== 'WAIT',
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
      scoreId: DEMO_SCORE_ID,
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
